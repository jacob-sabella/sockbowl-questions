package com.soulsoftworks.sockbowlquestions.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sockbowl.ratelimit.*} (plan m4-limits sections 2.2 and 2.10).
 */
@Data
@ConfigurationProperties("sockbowl.ratelimit")
public class RateLimitProperties {

    /** Master switch. When off every {@code tryConsume} is allowed and Redis is never touched. */
    private boolean enabled = true;

    /** Prefix of every bucket key: {@code {keyPrefix}:{policy}:u:{sub}}. */
    private String keyPrefix = UsageKeys.RL_PREFIX;

    /** Client ids ({@code azp}) whose tokens are the SERVICE tier. */
    private List<String> serviceClients = new ArrayList<>();

    /** Global per-tier multiplier applied to capacity and refill; missing tiers use 1.0. */
    private Map<Tier, Double> tierMultipliers = new EnumMap<>(Tier.class);

    private Map<String, PolicySpec> policies = new LinkedHashMap<>();

    /** REST route to policy mapping, evaluated by the request guard filter (WP-G2). */
    private List<Route> routes = new ArrayList<>();

    /** Ant patterns that are never limited. */
    private List<String> exempt = new ArrayList<>(List.of("/actuator/health", "/actuator/health/**"));

    private Redis redis = new Redis();

    private Events events = new Events();

    public PolicySpec policy(String name) {
        return policies.get(name);
    }

    /**
     * The multiplier for a tier on a policy: the policy's own entry wins over the
     * global one, and a missing entry is 1.0.
     */
    public double multiplierFor(PolicySpec spec, Tier tier) {
        if (spec != null && spec.getTierMultipliers() != null) {
            Double own = spec.getTierMultipliers().get(tier);
            if (own != null) {
                return own;
            }
        }
        Double global = tierMultipliers.get(tier);
        return global != null ? global : 1.0;
    }

    @Data
    public static class Route {
        /** HTTP method, or blank for any. */
        private String method;
        /** Ant path pattern. */
        private String pattern;
        private List<String> policies = new ArrayList<>();
        /**
         * Whether a request matching this route is also charged the fallback
         * policy ({@code default}, or {@code service} for the SERVICE tier). Set
         * {@code false} for a route whose own policy is the coarse cap, such as
         * questions' {@code POST /graphql} ({@code graphql-http}, 300/min, which a
         * 120/min {@code default} would otherwise shadow).
         */
        private boolean fallback = true;
    }

    @Data
    public static class Redis {
        /** Per-command timeout on the limiter's own Lettuce connection. */
        private Duration timeout = Duration.ofMillis(200);
        /** Connect timeout for that connection. */
        private Duration connectTimeout = Duration.ofMillis(500);
        /** After a failed connect, how long to wait before trying again (requests fail open meanwhile). */
        private Duration reconnectBackoff = Duration.ofSeconds(5);
    }

    @Data
    public static class Events {
        /** Service name written into each {@code rl:events} entry. */
        private String service = "game";
        /** Approximate stream length ({@code XADD MAXLEN ~}). */
        private long maxLen = 1000;
        /** At most one event per (policy, key) in this window. */
        private Duration sampleWindow = Duration.ofSeconds(10);
    }
}
