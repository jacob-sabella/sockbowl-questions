package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WP-G1 acceptance: the Redis-backed limiter triggers and recovers (plan
 * m4-limits section 4), driven by a {@link MutableClock} so recovery never sleeps.
 */
@Testcontainers
class RateLimitServiceIT {

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    private static final String POLICY = "session-create";

    private MutableClock clock;
    private RateLimitProperties properties;
    private RateLimitRedis redis;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingNow();
        properties = new RateLimitProperties();
        properties.getTierMultipliers().putAll(Map.of(Tier.GUEST, 1.0, Tier.PLAYER, 1.0, Tier.AUTHOR, 2.0));
        // 3 per minute, refilled greedily: one token every 20s.
        properties.getPolicies().put(POLICY, PolicySpec.of(3, Duration.ofMinutes(1), KeyBy.USER_OR_IP));
        properties.getPolicies().put("per-ip", PolicySpec.of(2, Duration.ofMinutes(1), KeyBy.IP));
        PolicySpec guestScaled = PolicySpec.of(5, Duration.ofMinutes(10), KeyBy.USER_OR_IP);
        guestScaled.getTierMultipliers().put(Tier.GUEST, 0.6);
        properties.getPolicies().put("guest-scaled", guestScaled);

        RateLimitTimeMeter timeMeter = new RateLimitTimeMeter(clock);
        redis = new RateLimitRedis(REDIS.getHost(), REDIS.getMappedPort(6379), 0, null,
                properties.getRedis(), timeMeter);
        redis.sync().flushdb();
        service = new RateLimitService(properties, redis, new BucketConfigurations(properties), clock);
    }

    @AfterEach
    void tearDown() {
        redis.destroy();
    }

    @Test
    void exhaustedPolicyRejectsWithRetryAfterAndRecoversAfterClockAdvance() {
        LimitSubject guest = LimitSubject.guest("203.0.113.7");

        for (int i = 0; i < 3; i++) {
            Decision d = service.tryConsume(POLICY, guest);
            assertThat(d.allowed()).as("call %d", i + 1).isTrue();
            assertThat(d.limit()).isEqualTo(3);
            assertThat(d.remaining()).isEqualTo(2 - i);
        }

        Decision rejected = service.tryConsume(POLICY, guest);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.failedOpen()).isFalse();
        assertThat(rejected.limiterUnavailable()).isFalse();
        assertThat(rejected.remaining()).isZero();
        // One token refills every 20s.
        assertThat(rejected.retryAfterNanos()).isEqualTo(Duration.ofSeconds(20).toNanos());
        assertThat(rejected.retryAfterSeconds()).isEqualTo(20);

        clock.advance(Duration.ofSeconds(19));
        assertThat(service.tryConsume(POLICY, guest).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.tryConsume(POLICY, guest).allowed()).as("one token back after 20s").isTrue();
        assertThat(service.tryConsume(POLICY, guest).allowed()).isFalse();

        // A full refill period restores the whole bucket.
        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            assertThat(service.tryConsume(POLICY, guest).allowed()).isTrue();
        }
        assertThat(service.tryConsume(POLICY, guest).allowed()).isFalse();
    }

    @Test
    void tierMultiplierScalesCapacity() {
        LimitSubject author = new LimitSubject("author-sub", "203.0.113.8", Tier.AUTHOR);

        for (int i = 0; i < 6; i++) {
            Decision d = service.tryConsume(POLICY, author);
            assertThat(d.allowed()).as("author call %d", i + 1).isTrue();
            assertThat(d.limit()).isEqualTo(6);
        }
        assertThat(service.tryConsume(POLICY, author).allowed()).isFalse();
    }

    @Test
    void policyLevelMultiplierOverridesGlobalOne() {
        LimitSubject guest = LimitSubject.guest("203.0.113.9");
        for (int i = 0; i < 3; i++) {
            assertThat(service.tryConsume("guest-scaled", guest).allowed()).isTrue();
        }
        Decision fourth = service.tryConsume("guest-scaled", guest);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.limit()).isEqualTo(3);

        LimitSubject player = new LimitSubject("p1", "203.0.113.9", Tier.PLAYER);
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsume("guest-scaled", player).allowed()).isTrue();
        }
        assertThat(service.tryConsume("guest-scaled", player).allowed()).isFalse();
    }

    @Test
    void differentSubjectsAndAddressesHaveIsolatedBuckets() {
        LimitSubject ipA = LimitSubject.guest("198.51.100.1");
        LimitSubject ipB = LimitSubject.guest("198.51.100.2");
        LimitSubject alice = new LimitSubject("alice", "198.51.100.1", Tier.PLAYER);
        LimitSubject bob = new LimitSubject("bob", "198.51.100.1", Tier.PLAYER);

        exhaust(POLICY, ipA, 3);
        assertThat(service.tryConsume(POLICY, ipA).allowed()).isFalse();
        assertThat(service.tryConsume(POLICY, ipB).allowed()).as("another IP").isTrue();
        assertThat(service.tryConsume(POLICY, alice).allowed()).as("a user on the same IP").isTrue();

        exhaust(POLICY, alice, 2);
        assertThat(service.tryConsume(POLICY, alice).allowed()).isFalse();
        assertThat(service.tryConsume(POLICY, bob).allowed()).as("another user").isTrue();

        assertThat(redis.sync().exists(
                UsageKeys.rateLimit(POLICY, "ip:198.51.100.1"),
                UsageKeys.rateLimit(POLICY, "ip:198.51.100.2"),
                UsageKeys.rateLimit(POLICY, "u:alice"),
                UsageKeys.rateLimit(POLICY, "u:bob"))).isEqualTo(4);
    }

    @Test
    void ipKeyedPolicyIgnoresTheUserSoUsersBehindOneAddressShareIt() {
        LimitSubject alice = new LimitSubject("alice", "198.51.100.3", Tier.PLAYER);
        LimitSubject bob = new LimitSubject("bob", "198.51.100.3", Tier.PLAYER);
        assertThat(service.tryConsume("per-ip", alice).allowed()).isTrue();
        assertThat(service.tryConsume("per-ip", bob).allowed()).isTrue();
        assertThat(service.tryConsume("per-ip", alice).allowed()).isFalse();
        assertThat(redis.sync().exists(UsageKeys.rateLimit("per-ip", "ip:198.51.100.3"))).isEqualTo(1);
    }

    @Test
    void bucketKeyExpiresNoLaterThanTheRefillPeriod() {
        LimitSubject guest = LimitSubject.guest("192.0.2.44");
        exhaust(POLICY, guest, 3);

        long ttlMs = redis.sync().pttl(UsageKeys.rateLimit(POLICY, "ip:192.0.2.44"));
        assertThat(ttlMs).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1).toMillis());
    }

    @Test
    void chargesSeveralTokensAtOnce() {
        LimitSubject guest = LimitSubject.guest("192.0.2.45");
        assertThat(service.tryConsume(POLICY, guest, 2).remaining()).isEqualTo(1);
        assertThat(service.tryConsume(POLICY, guest, 2).allowed()).isFalse();
        assertThat(service.tryConsume(POLICY, guest, 1).allowed()).isTrue();
    }

    @Test
    void disabledOrUnknownPolicyIsUnlimitedWithoutTouchingRedis() {
        LimitSubject guest = LimitSubject.guest("192.0.2.46");
        assertThat(service.tryConsume("no-such-policy", guest).allowed()).isTrue();

        properties.setEnabled(false);
        for (int i = 0; i < 10; i++) {
            assertThat(service.tryConsume(POLICY, guest).allowed()).isTrue();
        }
        assertThat(redis.sync().keys("rl:*")).isEmpty();
    }

    @Test
    void eventRecorderSamplesOneEventPerPolicyAndSubjectPerWindow() {
        properties.getEvents().setService("questions");
        RateLimitEventRecorder recorder = new RateLimitEventRecorder(redis, properties, clock);
        LimitSubject guest = LimitSubject.guest("192.0.2.47");
        LimitSubject user = new LimitSubject("carol", "192.0.2.48", Tier.PLAYER);

        for (int i = 0; i < 5; i++) {
            recorder.record(POLICY, RateLimitEventRecorder.KIND_RATE, guest, "/api/v1/session/create-new-game-session");
        }
        recorder.record(POLICY, RateLimitEventRecorder.KIND_RATE, user, "/x");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(redis.sync().xlen(UsageKeys.events())).isEqualTo(2));
        var entries = redis.sync().xrange(UsageKeys.events(), io.lettuce.core.Range.create("-", "+"));
        assertThat(entries.get(0).getBody())
                .containsEntry("svc", "questions")
                .containsEntry("policy", POLICY)
                .containsEntry("kind", "rate")
                .containsEntry("ip", "192.0.2.47")
                .containsEntry("path", "/api/v1/session/create-new-game-session")
                .doesNotContainKey("sub");
        assertThat(entries.get(1).getBody()).containsEntry("sub", "carol");
        assertThat(redis.sync().ttl(UsageKeys.eventSample(POLICY, "ip:192.0.2.47"))).isBetween(1L, 10L);
    }

    private void exhaust(String policy, LimitSubject subject, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(service.tryConsume(policy, subject).allowed()).isTrue();
        }
    }
}
