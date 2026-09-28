package com.soulsoftworks.sockbowlquestions.ban;

import com.soulsoftworks.sockbowlquestions.ratelimit.IpBannedException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.RedisUnavailableException;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Matching, refresh and failure rules of {@link RedisIpBanChecker} (WP-Q4), with
 * Redis mocked and a {@link MutableClock}.
 */
class RedisIpBanCheckerTest {

    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private final RateLimitRedis redis = mock(RateLimitRedis.class);
    private final Map<String, String> mirror = new LinkedHashMap<>();
    private RedisIpBanChecker checker;

    @BeforeEach
    void setUp() {
        when(redis.sync()).thenReturn(commands);
        when(commands.hgetall("ipban:all")).thenAnswer(inv -> new LinkedHashMap<>(mirror));
        checker = new RedisIpBanChecker(redis, clock, Duration.ofSeconds(15));
    }

    static String value(String cidr, Instant expiresAt) {
        return "{\"cidr\":\"" + cidr + "\",\"expiresAtEpochMs\":" + expiresAt.toEpochMilli() + "}";
    }

    @Test
    void matchesV4AndV6RangesFromGamesMirrorFormat() {
        mirror.put("a", value("10.1.0.0/16", NOW.plusSeconds(3600)));
        mirror.put("b", value("2001:db8:1::/48", NOW.plusSeconds(7200)));

        assertThat(checker.findActiveBan("10.1.200.3")).contains(NOW.plusSeconds(3600));
        assertThat(checker.findActiveBan("::ffff:10.1.2.3")).contains(NOW.plusSeconds(3600));
        assertThat(checker.findActiveBan("2001:db8:1:ffff::1")).contains(NOW.plusSeconds(7200));
        assertThat(checker.findActiveBan("10.2.0.1")).isEmpty();
        assertThat(checker.findActiveBan("unknown")).isEmpty();
        assertThatThrownBy(() -> checker.ensureNotBanned("10.1.0.1")).isInstanceOf(IpBannedException.class);
    }

    @Test
    void theMirrorIsReloadedOnlyAfterTheRefreshInterval() {
        assertThat(checker.findActiveBan("10.1.0.1")).isEmpty();
        mirror.put("a", value("10.1.0.1", NOW.plusSeconds(3600)));

        clock.advance(Duration.ofSeconds(14));
        assertThat(checker.findActiveBan("10.1.0.1")).isEmpty();
        verify(commands, times(1)).hgetall("ipban:all");

        clock.advance(Duration.ofSeconds(2));
        assertThat(checker.findActiveBan("10.1.0.1")).isPresent();
        verify(commands, times(2)).hgetall("ipban:all");
    }

    @Test
    void expiredEntriesNeverMatch() {
        mirror.put("old", value("10.1.0.1", NOW.minusSeconds(1)));
        mirror.put("soon", value("10.9.0.1", NOW.plusSeconds(5)));
        assertThat(checker.findActiveBan("10.1.0.1")).isEmpty();
        assertThat(checker.findActiveBan("10.9.0.1")).isPresent();
        clock.advance(Duration.ofSeconds(6));
        assertThat(checker.findActiveBan("10.9.0.1")).isEmpty();
    }

    @Test
    void malformedEntriesAreSkipped() {
        mirror.put("bad", "{nope");
        mirror.put("host", value("example.com", NOW.plusSeconds(60)));
        mirror.put("good", value("192.0.2.0/24", NOW.plusSeconds(60)));
        assertThat(checker.findActiveBan("192.0.2.5")).isPresent();
        assertThat(checker.loadedCount()).isEqualTo(1);
    }

    @Test
    void redisDownKeepsTheLastLoadedSet() {
        mirror.put("a", value("10.1.0.1", NOW.plusSeconds(3600)));
        assertThat(checker.findActiveBan("10.1.0.1")).isPresent();

        when(commands.hgetall("ipban:all")).thenThrow(new RedisUnavailableException("down", null));
        clock.advance(Duration.ofSeconds(20));
        assertThat(checker.findActiveBan("10.1.0.1")).isPresent();
        assertThat(checker.refresh()).isFalse();
    }

    @Test
    void redisDownFromTheStartFailsOpenAndRetriesAfterTheInterval() {
        when(redis.sync()).thenThrow(new RedisUnavailableException("connect failed", null));
        assertThat(checker.findActiveBan("10.1.0.1")).isEmpty();

        org.mockito.Mockito.reset(redis);
        when(redis.sync()).thenReturn(commands);
        mirror.put("a", value("10.1.0.1", NOW.plusSeconds(3600)));
        clock.advance(Duration.ofSeconds(16));
        assertThat(checker.findActiveBan("10.1.0.1")).isPresent();
    }
}
