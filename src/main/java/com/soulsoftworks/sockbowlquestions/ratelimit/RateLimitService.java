package com.soulsoftworks.sockbowlquestions.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed token buckets (plan m4-limits section 2.2).
 *
 * <p>{@link #tryConsume} never throws because of Redis: on any Redis error or
 * timeout a fail-open policy lets the call through ({@link Decision#failedOpen()})
 * and a {@code fail-closed} policy returns {@link Decision#unavailable()} (D12).
 * The Redis warning is logged at most once per minute.
 */
@Slf4j
public class RateLimitService {

    static final long WARN_INTERVAL_MS = 60_000;

    private final RateLimitProperties properties;
    private final RateLimitRedis redis;
    private final BucketConfigurations configurations;
    private final Clock clock;
    private final AtomicLong lastRedisWarnMs = new AtomicLong(Long.MIN_VALUE);
    private final Set<String> warnedUnknownPolicies = ConcurrentHashMap.newKeySet();

    public RateLimitService(RateLimitProperties properties, RateLimitRedis redis,
                            BucketConfigurations configurations, Clock clock) {
        this.properties = properties;
        this.redis = redis;
        this.configurations = configurations;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Decision tryConsume(String policy, LimitSubject subject) {
        return tryConsume(policy, subject, 1);
    }

    public Decision tryConsume(String policyName, LimitSubject subject, long tokens) {
        if (!properties.isEnabled()) {
            return Decision.unlimited();
        }
        PolicySpec spec = properties.policy(policyName);
        if (spec == null) {
            if (warnedUnknownPolicies.add(policyName)) {
                log.warn("Rate-limit policy '{}' is not configured; not limiting it", policyName);
            }
            return Decision.unlimited();
        }
        if (spec.getKeyBy() == KeyBy.CONNECTION) {
            throw new IllegalArgumentException("Policy '" + policyName
                    + "' is CONNECTION-keyed; charge it through LocalBucketRegistry");
        }
        BucketConfiguration configuration = configurations.forPolicy(policyName, spec, subject.tier());
        long limit = BucketConfigurations.capacityOf(configuration);
        String key = UsageKeys.rateLimit(properties.getKeyPrefix(), policyName, subject.keyFor(spec.getKeyBy()));
        try {
            ConsumptionProbe probe = redis.proxyManager().builder()
                    .build(key, () -> configuration)
                    .tryConsumeAndReturnRemaining(tokens);
            return probe.isConsumed()
                    ? Decision.allowed(probe.getRemainingTokens(), limit)
                    : Decision.rejected(probe.getNanosToWaitForRefill(), limit);
        } catch (RuntimeException e) {
            warnRedisFailure(policyName, e);
            return spec.isFailClosed() ? Decision.unavailable() : Decision.failedOpen(limit);
        }
    }

    private void warnRedisFailure(String policy, RuntimeException e) {
        long now = clock.millis();
        long last = lastRedisWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastRedisWarnMs.compareAndSet(last, now)) {
            log.warn("Rate limiter Redis unavailable (policy '{}'); fail-open policies are allowing requests"
                    + " and fail-closed policies are refusing them: {}", policy, e.toString());
        } else {
            log.debug("Rate limiter Redis unavailable (policy '{}'): {}", policy, e.toString());
        }
    }
}
