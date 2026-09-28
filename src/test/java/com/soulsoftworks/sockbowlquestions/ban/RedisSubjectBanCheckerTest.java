package com.soulsoftworks.sockbowlquestions.ban;

import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.RedisUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBanChecker.SubjectBan;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBannedException;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache, expiry and failure rules of {@link RedisSubjectBanChecker} (WP-Q4, D12/A6),
 * with Redis mocked and a {@link MutableClock}.
 */
class RedisSubjectBanCheckerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2031-01-01T00:00:00Z"));
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private final RateLimitRedis redis = mock(RateLimitRedis.class);
    private RedisSubjectBanChecker checker;

    @BeforeEach
    void setUp() {
        when(redis.sync()).thenReturn(commands);
        checker = new RedisSubjectBanChecker(redis, clock, Duration.ofSeconds(30), 1000);
    }

    @Test
    void readsGamesBanJsonAndThrowsTheRest403Exception() {
        when(commands.get("ban:kc-1")).thenReturn("{\"reason\":\"spam\",\"expiresAt\":\"2031-01-02T00:00:00Z\"}");

        assertThat(checker.findActiveBan("kc-1"))
                .contains(new SubjectBan("spam", Instant.parse("2031-01-02T00:00:00Z")));
        assertThatThrownBy(() -> checker.ensureNotBanned("kc-1"))
                .isInstanceOf(SubjectBannedException.class)
                .satisfies(e -> assertThat(((SubjectBannedException) e).body())
                        .containsEntry("error", "banned").containsEntry("reason", "spam")
                        .containsEntry("expiresAt", "2031-01-02T00:00:00Z"));
    }

    @Test
    void permanentBanHasNullExpiry() {
        when(commands.get("ban:kc-1")).thenReturn("{\"reason\":\"x\",\"expiresAt\":null}");
        assertThat(checker.findActiveBan("kc-1")).contains(new SubjectBan("x", null));
    }

    @Test
    void answersAreCachedForTheTtlThenReread() {
        when(commands.get("ban:kc-1")).thenReturn(null);
        assertThat(checker.findActiveBan("kc-1")).isEmpty();
        clock.advance(Duration.ofSeconds(29));
        assertThat(checker.findActiveBan("kc-1")).isEmpty();
        verify(commands, times(1)).get("ban:kc-1");

        when(commands.get("ban:kc-1")).thenReturn("{\"reason\":\"now\",\"expiresAt\":null}");
        clock.advance(Duration.ofSeconds(2));
        assertThat(checker.findActiveBan("kc-1")).isPresent();
        verify(commands, times(2)).get("ban:kc-1");
    }

    @Test
    void anExpiredBanNoLongerAppliesEvenFromTheCache() {
        when(commands.get("ban:kc-1")).thenReturn("{\"reason\":\"x\",\"expiresAt\":\"2031-01-01T00:00:10Z\"}");
        assertThat(checker.findActiveBan("kc-1")).isPresent();
        clock.advance(Duration.ofSeconds(11));
        assertThat(checker.findActiveBan("kc-1")).isEmpty();
    }

    @Test
    void redisDownWithNothingKnownFailsOpenAndIsNotCached() {
        when(commands.get("ban:kc-1")).thenThrow(new RedisUnavailableException("down", null));
        assertThat(checker.findActiveBan("kc-1")).isEmpty();

        // Redis is back: the very next request is checked (the failure wasn't cached).
        org.mockito.Mockito.reset(commands);
        when(commands.get("ban:kc-1")).thenReturn("{\"reason\":\"x\",\"expiresAt\":null}");
        assertThat(checker.findActiveBan("kc-1")).isPresent();
    }

    @Test
    void redisDownKeepsEnforcingAKnownBan() {
        when(commands.get("ban:kc-1")).thenReturn("{\"reason\":\"x\",\"expiresAt\":null}");
        assertThat(checker.findActiveBan("kc-1")).isPresent();

        when(commands.get("ban:kc-1")).thenThrow(new RedisUnavailableException("down", null));
        clock.advance(Duration.ofMinutes(5));
        assertThat(checker.findActiveBan("kc-1")).isPresent();
    }

    @Test
    void redisNeverConnectedFailsOpen() {
        when(redis.sync()).thenThrow(new RedisUnavailableException("connect failed", null));
        assertThat(checker.findActiveBan("kc-1")).isEmpty();
    }

    @Test
    void anUnreadableValueIsAPermanentBan() {
        assertThat(RedisSubjectBanChecker.parse("kc", "garbage{")).isEqualTo(new SubjectBan(null, null));
        assertThat(RedisSubjectBanChecker.parse("kc", "[1,2]")).isEqualTo(new SubjectBan(null, null));
        assertThat(RedisSubjectBanChecker.parse("kc", "{\"expiresAt\":\"not-a-date\"}"))
                .isEqualTo(new SubjectBan(null, null));
    }

    @Test
    void blankSubjectsAreNeverLookedUp() {
        assertThat(checker.findActiveBan(null)).isEqualTo(Optional.empty());
        assertThat(checker.findActiveBan(" ")).isEqualTo(Optional.empty());
        org.mockito.Mockito.verifyNoInteractions(commands);
    }
}
