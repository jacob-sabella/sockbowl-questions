package com.soulsoftworks.sockbowlquestions.ratelimit.graphql;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code sockbowl.ratelimit.graphql.*} (plan m4-limits section 2.2, WP-Q2): the
 * field-to-policy map of the per-field limiter and the static depth and
 * complexity caps. {@code sockbowl.ratelimit.enabled} switches the per-field
 * limiter off with the rest of the limiter; the depth and complexity caps are
 * static and always apply.
 */
@Data
@ConfigurationProperties("sockbowl.ratelimit.graphql")
public class GraphQlLimitsProperties {

    /** Policy charged for a top-level Query field that has no entry in {@link #fields}. */
    private String defaultQueryPolicy = "graphql-read";

    /** Policy charged for a top-level Mutation field that has no entry in {@link #fields}. */
    private String defaultMutationPolicy = "graphql-write";

    /**
     * Policy charged by the SERVICE tier (the game backend's client-credentials
     * token) for every top-level field, instead of the mapped policy.
     */
    private String servicePolicy = "service";

    /** Top-level field name to policy; wins over the defaults. */
    private Map<String, String> fields = new LinkedHashMap<>();

    /** Deepest selection accepted (graphql-java's {@code MaxQueryDepthInstrumentation} count). */
    private int maxDepth = 15;

    /**
     * Highest query complexity accepted: one point per selected field, with
     * introspection fields free. See {@code GraphQlLimitsConfig} for the
     * calibration.
     */
    private int maxComplexity = 82;
}
