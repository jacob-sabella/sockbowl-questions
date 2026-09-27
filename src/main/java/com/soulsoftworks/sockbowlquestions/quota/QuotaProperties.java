package com.soulsoftworks.sockbowlquestions.quota;

import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code sockbowl.quota.*}: per-tier quota defaults (D10, D11; plan m4-limits
 * section 2.3). {@code -1} means unlimited. Metric names that contain a dot
 * must use bracket notation in properties, e.g.
 * {@code sockbowl.quota.tiers.author.[ai.generations]=20}.
 */
@Data
@ConfigurationProperties("sockbowl.quota")
public class QuotaProperties {

    public static final long UNLIMITED = -1;

    private boolean enabled = true;

    /** A hosted session with no activity for this long no longer counts (WP-G5). */
    private Duration sessionIdleTimeout = Duration.ofMinutes(30);

    /** Minimum interval between activity touches of one hosted session (WP-G5). */
    private Duration sessionTouchInterval = Duration.ofMinutes(1);

    /** TTL of the daily counters. */
    private Duration dailyTtl = Duration.ofHours(48);

    private Map<Tier, Map<String, Long>> tiers = new EnumMap<>(Tier.class);

    /**
     * The configured default for a tier and metric. ADMIN and SERVICE are
     * unlimited unless configured otherwise; any other unconfigured pair is 0
     * (deny), so a missing key can never silently grant unlimited use.
     */
    public long defaultLimit(Tier tier, String metric) {
        Map<String, Long> byMetric = tiers.getOrDefault(tier, new LinkedHashMap<>());
        Long configured = byMetric.get(metric);
        if (configured != null) {
            return configured;
        }
        return tier.skipsQuotas() ? UNLIMITED : 0;
    }
}
