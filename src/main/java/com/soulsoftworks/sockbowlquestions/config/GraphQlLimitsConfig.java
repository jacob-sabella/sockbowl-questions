package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlquestions.ratelimit.graphql.GraphQlLimitsProperties;
import com.soulsoftworks.sockbowlquestions.ratelimit.graphql.RateLimitingInstrumentation;
import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GraphQL limits (plan m4-limits WP-Q2). Spring Boot's GraphQL auto-configuration
 * picks up every {@link graphql.execution.instrumentation.Instrumentation} bean:
 *
 * <ul>
 *   <li>{@link RateLimitingInstrumentation}: per top-level field rate limits
 *       ({@code graphql-read}, {@code graphql-write}, {@code service}).</li>
 *   <li>{@link MaxQueryDepthInstrumentation}: {@code sockbowl.ratelimit.graphql.max-depth}
 *       (15). The data schema has no cycles (its deepest client document is depth 6, counting the top-level field as 1), so
 *       only introspection, or a future cyclic type, can get near it; the standard
 *       introspection query stays under it.</li>
 *   <li>{@link MaxQueryComplexityInstrumentation}: {@code sockbowl.ratelimit.graphql.max-complexity}
 *       with {@link #COMPLEXITY_CALCULATOR}.</li>
 * </ul>
 *
 * Both caps abort the whole operation before any data fetcher runs (an
 * {@code ExecutionAborted} error, HTTP 200, no data), so a rejected document is
 * never charged a field token.
 *
 * <h2>Complexity calibration (2026-09-27)</h2>
 * One point per selected field, fragments expanded, introspection fields
 * ({@code __schema}, {@code __type}, {@code __typename}) and their selections free.
 * Every GraphQL document the clients send was measured by
 * {@code GraphQlDepthComplexityTest}; the documents are copied verbatim under
 * {@code src/test/resources/graphql-documents/}:
 *
 * <table>
 *   <caption>Largest client documents</caption>
 *   <tr><th>Document</th><th>Complexity</th><th>Depth</th></tr>
 *   <tr><td>game {@code PacketClient.QUERY} (getPacketById)</td><td>41</td><td>6</td></tr>
 *   <tr><td>ng {@code getAllPackets}, {@code getPacketById}</td><td>38</td><td>6</td></tr>
 *   <tr><td>ng {@code searchPacketsByName}</td><td>11</td><td>3</td></tr>
 *   <tr><td>every ng authoring mutation</td><td>1 to 6</td><td>1 to 3</td></tr>
 * </table>
 *
 * The cap is twice the maximum: {@code 2 x 41 = 82}. INT1 re-measures with M3's
 * documents (still 41: the largest of the new documents, {@code importPacket},
 * is smaller); the test fails if any document loses its 2x headroom.
 *
 * <h2>{@code getAllPackets} (Q-V1-07, INT1)</h2>
 * Every other field's cost bounds field-<i>selection</i> breadth, not
 * result-<i>set</i> size, which is fine for every other query: they return one
 * packet, a fixed small list (taxonomy), or a paginated page whose {@code size}
 * argument a future WP can cost directly. The deprecated, unpaginated
 * {@code getAllPackets} (kept only for {@code listPackets}'s not-yet-migrated
 * callers, per its own deprecation notice) returns every visible packet's full
 * tree at once, so a selection costed like any other field could be aliased
 * (e.g. three {@code a: getAllPackets { ... } b: getAllPackets { ... } c: ...})
 * to pull hundreds of full packet trees a minute for the same field-selection
 * price as one. {@link #GET_ALL_PACKETS_LIST_WEIGHT} multiplies just that
 * field's child complexity by an assumed result-size factor, so even a single
 * full-tree call is rejected, while the small selections the surviving real
 * callers (integration tests exercising visibility/redaction, not abuse) send
 * stay comfortably under the cap; see {@code GraphQlDepthComplexityTest}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphQlLimitsProperties.class)
public class GraphQlLimitsConfig {

    /**
     * Q-V1-07: an assumed result-size factor for the deprecated, unpaginated
     * {@code getAllPackets} (see the class Javadoc); chosen so a single
     * full-packet-tree selection is rejected outright while every surviving
     * small real selection stays well under the cap.
     */
    public static final int GET_ALL_PACKETS_LIST_WEIGHT = 10;

    /**
     * One point per field plus its children; introspection fields cost
     * nothing; {@code getAllPackets} additionally weights its children by
     * {@link #GET_ALL_PACKETS_LIST_WEIGHT} (Q-V1-07).
     */
    public static final FieldComplexityCalculator COMPLEXITY_CALCULATOR = (environment, childComplexity) -> {
        String name = environment.getField().getName();
        if (name.startsWith("__")) {
            return 0;
        }
        if (name.equals("getAllPackets")) {
            return 1 + GET_ALL_PACKETS_LIST_WEIGHT * childComplexity;
        }
        return 1 + childComplexity;
    };

    @Bean
    public RateLimitingInstrumentation rateLimitingInstrumentation(RateLimitService rateLimitService,
                                                                   LimitSubjectResolver subjectResolver,
                                                                   GraphQlLimitsProperties properties) {
        return new RateLimitingInstrumentation(rateLimitService, subjectResolver, properties);
    }

    @Bean
    public MaxQueryDepthInstrumentation maxQueryDepthInstrumentation(GraphQlLimitsProperties properties) {
        return new MaxQueryDepthInstrumentation(properties.getMaxDepth());
    }

    @Bean
    public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation(GraphQlLimitsProperties properties) {
        return new MaxQueryComplexityInstrumentation(properties.getMaxComplexity(), COMPLEXITY_CALCULATOR);
    }
}
