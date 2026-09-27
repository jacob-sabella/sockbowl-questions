package com.soulsoftworks.sockbowlquestions.ratelimit;

import io.github.bucket4j.BucketConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds (and memoizes) the tier-scaled bucket4j configuration of a policy.
 * Shared by the Redis-backed {@link RateLimitService} and the in-memory
 * {@link LocalBucketRegistry} so both size a policy the same way.
 */
public class BucketConfigurations {

    private final RateLimitProperties properties;
    private final Map<String, BucketConfiguration> cache = new ConcurrentHashMap<>();

    public BucketConfigurations(RateLimitProperties properties) {
        this.properties = properties;
    }

    public BucketConfiguration forPolicy(String policyName, PolicySpec spec, Tier tier) {
        return cache.computeIfAbsent(policyName + "|" + tier, k -> build(spec, tier));
    }

    private BucketConfiguration build(PolicySpec spec, Tier tier) {
        double multiplier = properties.multiplierFor(spec, tier);
        long capacity = scale(spec.getCapacity(), multiplier);
        long refill = scale(spec.effectiveRefillTokens(), multiplier);
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(refill, spec.getRefillPeriod()))
                .build();
    }

    static long scale(long value, double multiplier) {
        return Math.max(1, Math.round(value * multiplier));
    }

    /** The effective capacity of a configuration (its first and only bandwidth). */
    public static long capacityOf(BucketConfiguration configuration) {
        return configuration.getBandwidths()[0].getCapacity();
    }
}
