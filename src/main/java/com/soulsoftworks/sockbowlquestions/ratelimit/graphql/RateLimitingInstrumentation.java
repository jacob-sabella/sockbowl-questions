package com.soulsoftworks.sockbowlquestions.ratelimit.graphql;

import com.soulsoftworks.sockbowlquestions.ratelimit.Decision;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimiterUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;

/**
 * Field-level GraphQL rate limiting (plan m4-limits section 2.2, WP-Q2, the
 * GraphQL half of M4-RL-04).
 *
 * <p>Every <b>top-level</b> Query and Mutation field is charged one token of its
 * policy just before its data fetcher runs, so a request that aliases the same
 * mutation 70 times is charged 70 times, not once. Nested fields are never
 * charged. The policy is {@code sockbowl.ratelimit.graphql.fields.<fieldName>}
 * when mapped, otherwise {@code default-mutation-policy} ({@code graphql-write})
 * for a mutation and {@code default-query-policy} ({@code graphql-read}) for
 * anything else. A SERVICE-tier caller (the game backend) is charged the
 * {@code service} ceiling policy instead, so game's packet loads never compete
 * with a user budget. {@code __typename} is never charged.
 *
 * <p>A rejected field throws {@link RateLimitExceededException} (or
 * {@link LimiterUnavailableException} for a fail-closed policy with Redis down)
 * from the data fetcher, before the real fetcher runs, and
 * {@code LimitsGraphQlExceptionResolver} turns it into a field error classified
 * {@code RATE_LIMITED} with {@code policy} and {@code retryAfterSeconds}
 * extensions. The HTTP status stays 200; only the coarse {@code graphql-http}
 * REST policy answers a real 429.
 *
 * <p>The caller is the {@link LimitSubject} that {@link LimitSubjectGraphQlInterceptor}
 * put in the {@code GraphQLContext}; outside the web transport it falls back to
 * {@link LimitSubjectResolver#current()}. With {@code sockbowl.ratelimit.enabled=false}
 * data fetchers are returned unwrapped.
 *
 * <p>Note on aliased mutations: mutation fields run serially, and every
 * {@code Mutation} field in this schema is non-null, so the first rejected field
 * nulls {@code data} and graphql-java stops the remaining serial fields (spec
 * error propagation). Those later fields are neither executed nor charged; the
 * fields before the rejection have already run.
 */
@Slf4j
public class RateLimitingInstrumentation extends SimplePerformantInstrumentation {

    private final RateLimitService rateLimitService;
    private final LimitSubjectResolver subjectResolver;
    private final GraphQlLimitsProperties properties;

    public RateLimitingInstrumentation(RateLimitService rateLimitService, LimitSubjectResolver subjectResolver,
                                       GraphQlLimitsProperties properties) {
        this.rateLimitService = rateLimitService;
        this.subjectResolver = subjectResolver;
        this.properties = properties;
    }

    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
                                                InstrumentationFieldFetchParameters parameters,
                                                InstrumentationState state) {
        if (!rateLimitService.isEnabled() || !isTopLevel(parameters)) {
            return dataFetcher;
        }
        return environment -> {
            charge(environment);
            return dataFetcher.get(environment);
        };
    }

    private static boolean isTopLevel(InstrumentationFieldFetchParameters parameters) {
        return parameters.getExecutionStepInfo() != null
                && parameters.getExecutionStepInfo().getPath().getLevel() == 1;
    }

    void charge(DataFetchingEnvironment environment) {
        String fieldName = environment.getField().getName();
        if (fieldName.startsWith("__typename")) {
            return;
        }
        LimitSubject subject = subjectOf(environment);
        if (subject == null) {
            return;
        }
        String policy = policyFor(fieldName, operationOf(environment), subject.tier());
        Decision decision = rateLimitService.tryConsume(policy, subject);
        if (decision.limiterUnavailable()) {
            throw new LimiterUnavailableException(policy);
        }
        if (!decision.allowed()) {
            log.debug("GraphQL field '{}' rejected by policy '{}' for {}", fieldName, policy, subject);
            throw RateLimitExceededException.from(policy, decision);
        }
    }

    /** The policy charged for a top-level field of the given operation type, for a caller of the given tier. */
    public String policyFor(String fieldName, OperationDefinition.Operation operation, Tier tier) {
        if (tier == Tier.SERVICE) {
            return properties.getServicePolicy();
        }
        String mapped = properties.getFields().get(fieldName);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        return operation == OperationDefinition.Operation.MUTATION
                ? properties.getDefaultMutationPolicy()
                : properties.getDefaultQueryPolicy();
    }

    private static OperationDefinition.Operation operationOf(DataFetchingEnvironment environment) {
        OperationDefinition operation = environment.getOperationDefinition();
        return operation == null ? OperationDefinition.Operation.QUERY : operation.getOperation();
    }

    private LimitSubject subjectOf(DataFetchingEnvironment environment) {
        LimitSubject subject = LimitSubjectGraphQlInterceptor.subjectOf(environment);
        if (subject != null) {
            return subject;
        }
        try {
            return subjectResolver.current();
        } catch (RuntimeException e) {
            log.debug("No limit subject for GraphQL field '{}'; not charging it", environment.getField().getName(), e);
            return null;
        }
    }
}
