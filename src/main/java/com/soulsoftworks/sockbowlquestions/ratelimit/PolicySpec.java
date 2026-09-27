package com.soulsoftworks.sockbowlquestions.ratelimit;

import lombok.Data;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * One named token-bucket policy, bound from
 * {@code sockbowl.ratelimit.policies.<name>.*}.
 *
 * <p>The bucket holds {@link #capacity} tokens and refills
 * {@link #refillTokens} (default: the capacity) greedily over
 * {@link #refillPeriod}. Capacity and refill are scaled by the caller's tier
 * multiplier; a policy-level {@link #tierMultipliers} entry overrides the global
 * one for that tier.
 */
@Data
public class PolicySpec {

    private long capacity;
    /** Tokens added per {@link #refillPeriod}; {@code 0} or unset means "equal to capacity". */
    private long refillTokens;
    private Duration refillPeriod = Duration.ofMinutes(1);
    private KeyBy keyBy = KeyBy.USER_OR_IP;
    /** When Redis is unavailable, reject with 503 instead of letting the request through (D12). */
    private boolean failClosed;
    private Map<Tier, Double> tierMultipliers = new EnumMap<>(Tier.class);

    public long effectiveRefillTokens() {
        return refillTokens > 0 ? refillTokens : capacity;
    }

    public static PolicySpec of(long capacity, Duration refillPeriod, KeyBy keyBy) {
        PolicySpec spec = new PolicySpec();
        spec.setCapacity(capacity);
        spec.setRefillPeriod(refillPeriod);
        spec.setKeyBy(keyBy);
        return spec;
    }
}
