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
 * The cap is twice the maximum: {@code 2 x 41 = 82}. A document that selects the
 * full packet tree three times (for example three aliased {@code getAllPackets})
 * is rejected. INT1 re-measures with M3's documents; the test fails if any
 * document loses its 2x headroom.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphQlLimitsProperties.class)
public class GraphQlLimitsConfig {

    /** One point per field plus its children; introspection fields cost nothing. */
    public static final FieldComplexityCalculator COMPLEXITY_CALCULATOR = (environment, childComplexity) ->
            environment.getField().getName().startsWith("__") ? 0 : 1 + childComplexity;

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
