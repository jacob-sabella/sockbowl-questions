package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory bucket4j buckets for the STOMP policies (plan m4-limits section
 * 2.2): connections are instance-local, so charging a SEND costs no Redis round
 * trip. Buckets are grouped by a local key (a connection,
 * {@link UsageKeys#connectionPart}, or an address, {@link UsageKeys#ipPart}),
 * expire 10 minutes after last use, and are dropped with {@link #invalidate}
 * when the connection closes. Same {@link PolicySpec}s and the same clock as
 * the Redis buckets.
 */
@Slf4j
public class LocalBucketRegistry {

    static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    private final RateLimitProperties properties;
    private final BucketConfigurations configurations;
    private final RateLimitTimeMeter timeMeter;
    private final Cache<String, ConcurrentMap<String, Bucket>> buckets;

    public LocalBucketRegistry(RateLimitProperties properties, BucketConfigurations configurations,
                               RateLimitTimeMeter timeMeter) {
        this.properties = properties;
        this.configurations = configurations;
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder().expireAfterAccess(EXPIRE_AFTER_ACCESS).build();
    }

    public Decision tryConsume(String localKey, String policyName, Tier tier) {
        return tryConsume(localKey, policyName, tier, 1);
    }

    public Decision tryConsume(String localKey, String policyName, Tier tier, long tokens) {
        if (!properties.isEnabled()) {
            return Decision.unlimited();
        }
        PolicySpec spec = properties.policy(policyName);
        if (spec == null) {
            log.debug("Local rate-limit policy '{}' is not configured; not limiting it", policyName);
            return Decision.unlimited();
        }
        BucketConfiguration configuration = configurations.forPolicy(policyName, spec, tier);
        long limit = BucketConfigurations.capacityOf(configuration);
        Bucket bucket = buckets.get(localKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(policyName, p -> Bucket.builder()
                        .addLimit(configuration.getBandwidths()[0])
                        .withCustomTimePrecision(timeMeter)
                        .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(tokens);
        return probe.isConsumed()
                ? Decision.allowed(probe.getRemainingTokens(), limit)
                : Decision.rejected(probe.getNanosToWaitForRefill(), limit);
    }

    /** Drops every bucket held under a local key (e.g. on {@code SessionDisconnectEvent}). */
    public void invalidate(String localKey) {
        buckets.invalidate(localKey);
    }

    /** Number of local keys currently holding buckets (diagnostics/tests). */
    public long size() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
