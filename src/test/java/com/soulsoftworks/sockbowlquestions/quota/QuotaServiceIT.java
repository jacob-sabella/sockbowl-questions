package com.soulsoftworks.sockbowlquestions.quota;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitProperties;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitTimeMeter;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-G1 acceptance for {@link QuotaService} (D10, D11; plan m4-limits section
 * 2.3): a daily counter rejects at the limit and allows again after UTC
 * midnight, overrides win over tier defaults, -1 is unlimited, refunds give
 * units back.
 */
@Testcontainers
class QuotaServiceIT {

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    private static final String METRIC = UsageKeys.AI_GENERATIONS;
    private static final Instant LATE_EVENING = Instant.parse("2026-09-27T23:58:00Z");

    private MutableClock clock;
    private QuotaProperties properties;
    private RateLimitRedis redis;
    private QuotaService quotas;

    private final LimitSubject author = new LimitSubject("author-1", "192.0.2.10", Tier.AUTHOR);

    @BeforeEach
    void setUp() {
        clock = new MutableClock(LATE_EVENING);
        properties = new QuotaProperties();
        tier(Tier.AUTHOR).put(METRIC, 3L);
        tier(Tier.PLAYER).put(METRIC, 0L);
        tier(Tier.GUEST).put(METRIC, 0L);
        tier(Tier.GUEST).put(UsageKeys.HOSTED_SESSIONS, 2L);
        tier(Tier.ADMIN).put(METRIC, -1L);

        redis = new RateLimitRedis(REDIS.getHost(), REDIS.getMappedPort(6379), 0, null,
                new RateLimitProperties().getRedis(), new RateLimitTimeMeter(clock));
        redis.sync().flushdb();
        quotas = new QuotaService(properties, redis, clock);
    }

    @AfterEach
    void tearDown() {
        redis.destroy();
    }

    @Test
    void dailyCounterRejectsAtTheLimitAndRecoversAfterUtcMidnight() {
        for (int i = 1; i <= 3; i++) {
            QuotaStatus status = quotas.consumeDaily(author, METRIC);
            assertThat(status.used()).isEqualTo(i);
            assertThat(status.limit()).isEqualTo(3);
            assertThat(status.resetsAt()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        }

        assertThatThrownBy(() -> quotas.consumeDaily(author, METRIC))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                    assertThat(e.getMetric()).isEqualTo(METRIC);
                    assertThat(e.getLimit()).isEqualTo(3);
                    assertThat(e.getUsed()).isEqualTo(3);
                    assertThat(e.getResetsAt()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
                    assertThat(e.body()).containsEntry("error", "quota_exceeded")
                            .containsEntry("resetsAt", "2026-09-28T00:00:00Z");
                });
        // A rejected charge is not counted.
        assertThat(redis.sync().get(UsageKeys.daily("author-1", METRIC, LocalDate.of(2026, 9, 27)))).isEqualTo("3");

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> quotas.consumeDaily(author, METRIC)).isInstanceOf(QuotaExceededException.class);

        clock.advance(Duration.ofMinutes(1)); // 2026-09-28T00:00:00Z
        QuotaStatus nextDay = quotas.consumeDaily(author, METRIC);
        assertThat(nextDay.used()).isEqualTo(1);
        assertThat(nextDay.resetsAt()).isEqualTo(Instant.parse("2026-09-29T00:00:00Z"));
        assertThat(redis.sync().exists(UsageKeys.daily("author-1", METRIC, LocalDate.of(2026, 9, 28)))).isEqualTo(1);
    }

    @Test
    void dailyCounterGetsA48HourTtlOnCreation() {
        quotas.consumeDaily(author, METRIC);
        long ttl = redis.sync().ttl(UsageKeys.daily("author-1", METRIC, LocalDate.of(2026, 9, 27)));
        assertThat(ttl).isBetween(Duration.ofHours(47).toSeconds(), Duration.ofHours(48).toSeconds());
    }

    @Test
    void overrideRaisesTheLimit() {
        for (int i = 0; i < 3; i++) {
            quotas.consumeDaily(author, METRIC);
        }
        assertThatThrownBy(() -> quotas.consumeDaily(author, METRIC)).isInstanceOf(QuotaExceededException.class);

        redis.sync().hset(UsageKeys.quotaOverride("author-1"), METRIC, "5");
        assertThat(quotas.effectiveLimit(author, METRIC)).isEqualTo(5);
        assertThat(quotas.consumeDaily(author, METRIC).limit()).isEqualTo(5);
        assertThat(quotas.consumeDaily(author, METRIC).used()).isEqualTo(5);
        assertThatThrownBy(() -> quotas.consumeDaily(author, METRIC)).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void overrideCanLowerAndGrantToAPlayerWhoseDefaultIsZero() {
        LimitSubject player = new LimitSubject("player-1", "192.0.2.11", Tier.PLAYER);
        assertThatThrownBy(() -> quotas.consumeDaily(player, METRIC)).isInstanceOf(QuotaExceededException.class);

        redis.sync().hset(UsageKeys.quotaOverride("player-1"), METRIC, "1");
        assertThat(quotas.consumeDaily(player, METRIC).used()).isEqualTo(1);
        assertThatThrownBy(() -> quotas.consumeDaily(player, METRIC)).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void minusOneMeansUnlimited() {
        redis.sync().hset(UsageKeys.quotaOverride("author-1"), METRIC, "-1");
        for (int i = 0; i < 25; i++) {
            QuotaStatus status = quotas.consumeDaily(author, METRIC);
            assertThat(status.unlimited()).isTrue();
        }
        assertThat(quotas.effectiveLimit(author, METRIC)).isEqualTo(QuotaProperties.UNLIMITED);

        LimitSubject admin = new LimitSubject("admin-1", "192.0.2.12", Tier.ADMIN);
        for (int i = 0; i < 25; i++) {
            assertThat(quotas.consumeDaily(admin, METRIC).unlimited()).isTrue();
        }
        // Admin use is still counted, for visibility.
        assertThat(quotas.dailyStatus(admin, METRIC).used()).isEqualTo(25);
        assertThat(quotas.effectiveLimit(admin, METRIC)).isEqualTo(QuotaProperties.UNLIMITED);
    }

    @Test
    void serviceTierIsNeverCounted() {
        LimitSubject svc = new LimitSubject("service-account", "192.0.2.13", Tier.SERVICE);
        for (int i = 0; i < 10; i++) {
            assertThat(quotas.consumeDaily(svc, METRIC).unlimited()).isTrue();
        }
        assertThat(redis.sync().keys("usage:*")).isEmpty();
    }

    @Test
    void refundDecrementsAndNeverGoesBelowZeroOrCreatesAKey() {
        quotas.consumeDaily(author, METRIC);
        quotas.consumeDaily(author, METRIC);
        quotas.refundDaily(author, METRIC, 1);
        assertThat(quotas.dailyStatus(author, METRIC).used()).isEqualTo(1);

        quotas.refundDaily(author, METRIC, 5);
        assertThat(quotas.dailyStatus(author, METRIC).used()).isZero();
        long ttl = redis.sync().ttl(UsageKeys.daily("author-1", METRIC, LocalDate.of(2026, 9, 27)));
        assertThat(ttl).as("refund keeps the TTL").isPositive();

        LimitSubject other = new LimitSubject("author-2", "192.0.2.14", Tier.AUTHOR);
        quotas.refundDaily(other, METRIC, 1);
        assertThat(redis.sync().exists(UsageKeys.daily("author-2", METRIC, LocalDate.of(2026, 9, 27)))).isZero();

        // After a refund the unit can be used again.
        quotas.consumeDaily(author, METRIC);
        quotas.consumeDaily(author, METRIC);
        quotas.consumeDaily(author, METRIC);
        assertThatThrownBy(() -> quotas.consumeDaily(author, METRIC)).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void anonymousCallerWithAZeroDefaultIsRejectedWithoutARoundTrip() {
        LimitSubject guest = LimitSubject.guest("192.0.2.15");
        assertThatThrownBy(() -> quotas.consumeDaily(guest, METRIC))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> assertThat(e.getLimit()).isZero());
        assertThat(redis.sync().keys("usage:*")).isEmpty();
        assertThat(quotas.effectiveLimit(guest, UsageKeys.HOSTED_SESSIONS)).isEqualTo(2);
    }

    @Test
    void globalDailyBudgetRejectsAtItsLimitAndRollsOver() {
        String metric = UsageKeys.AI_SERVERKEY;
        quotas.consumeGlobalDaily(metric, 2, 1, true);
        quotas.consumeGlobalDaily(metric, 2, 1, true);
        assertThatThrownBy(() -> quotas.consumeGlobalDaily(metric, 2, 1, true))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(redis.sync().get(UsageKeys.globalDaily(metric, LocalDate.of(2026, 9, 27)))).isEqualTo("2");

        quotas.refundGlobalDaily(metric, 1);
        assertThat(quotas.consumeGlobalDaily(metric, 2, 1, true).used()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        assertThat(quotas.consumeGlobalDaily(metric, 2, 1, true).used()).isEqualTo(1);
    }

    @Test
    void disabledQuotasAreUnlimitedAndUntouched() {
        properties.setEnabled(false);
        for (int i = 0; i < 10; i++) {
            assertThat(quotas.consumeDaily(author, METRIC).unlimited()).isTrue();
        }
        assertThat(quotas.effectiveLimit(author, METRIC)).isEqualTo(QuotaProperties.UNLIMITED);
        assertThat(redis.sync().keys("usage:*")).isEmpty();
    }

    private Map<String, Long> tier(Tier tier) {
        return properties.getTiers().computeIfAbsent(tier, t -> new LinkedHashMap<>());
    }
}
