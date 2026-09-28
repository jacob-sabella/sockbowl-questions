package com.soulsoftworks.sockbowlquestions.ratelimit.graphql;

import com.soulsoftworks.sockbowlquestions.ratelimit.Decision;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimiterUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.language.OperationDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link RateLimitingInstrumentation} on a tiny executable schema:
 * which fields are charged, which policy they are charged, and what a rejection
 * throws. The Redis-backed behaviour is proven by {@code GraphQlRateLimitIT}.
 */
class RateLimitingInstrumentationTest {

    private static final String SDL = """
            type Query { a: String, b: Obj, special: String }
            type Obj { c: String, d: Obj }
            type Mutation { m: String, special: String }
            """;

    private static final LimitSubject PLAYER = new LimitSubject("sub-1", "10.0.0.1", Tier.PLAYER);
    private static final LimitSubject SERVICE = new LimitSubject("svc", "10.0.0.2", Tier.SERVICE);

    private RateLimitService rateLimitService;
    private LimitSubjectResolver subjectResolver;
    private GraphQlLimitsProperties properties;
    private final List<Throwable> fetcherExceptions = new ArrayList<>();
    private GraphQL graphQL;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        subjectResolver = mock(LimitSubjectResolver.class);
        when(rateLimitService.isEnabled()).thenReturn(true);
        when(rateLimitService.tryConsume(anyString(), any())).thenReturn(Decision.allowed(10, 11));
        properties = new GraphQlLimitsProperties();
        properties.getFields().put("special", "special-policy");

        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t.dataFetcher("a", env -> "a")
                        .dataFetcher("b", env -> Map.of("c", "c", "d", Map.of("c", "cc")))
                        .dataFetcher("special", env -> "s"))
                .type("Mutation", t -> t.dataFetcher("m", env -> "m").dataFetcher("special", env -> "s"))
                .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
        DataFetcherExceptionHandler recording = parameters -> {
            fetcherExceptions.add(parameters.getException());
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().build());
        };
        graphQL = GraphQL.newGraphQL(schema)
                .instrumentation(new RateLimitingInstrumentation(rateLimitService, subjectResolver, properties))
                .defaultDataFetcherExceptionHandler(recording)
                .build();
    }

    private ExecutionResult execute(String query, LimitSubject subject) {
        ExecutionInput.Builder input = ExecutionInput.newExecutionInput(query);
        if (subject != null) {
            input.graphQLContext(Map.of(LimitSubjectGraphQlInterceptor.SUBJECT_KEY, subject));
        }
        return graphQL.execute(input.build());
    }

    @Test
    void eachTopLevelQueryFieldIsChargedGraphqlReadAndNestedFieldsAreNot() {
        ExecutionResult result = execute("{ a x: a b { c d { c } } __typename }", PLAYER);

        assertThat(result.getErrors()).isEmpty();
        verify(rateLimitService, times(3)).tryConsume("graphql-read", PLAYER);
        verify(rateLimitService, times(3)).tryConsume(anyString(), any());
    }

    @Test
    void eachTopLevelMutationFieldIsChargedGraphqlWrite() {
        execute("mutation { m one: m two: m }", PLAYER);

        verify(rateLimitService, times(3)).tryConsume("graphql-write", PLAYER);
    }

    @Test
    void aMappedFieldIsChargedItsPolicyForQueriesAndMutations() {
        execute("{ special }", PLAYER);
        execute("mutation { special }", PLAYER);

        verify(rateLimitService, times(2)).tryConsume("special-policy", PLAYER);
    }

    @Test
    void theServiceTierIsChargedTheServicePolicyForEveryField() {
        execute("{ a special }", SERVICE);
        execute("mutation { m }", SERVICE);

        verify(rateLimitService, times(3)).tryConsume("service", SERVICE);
        verify(rateLimitService, times(3)).tryConsume(anyString(), any());
    }

    @Test
    void policyForFollowsTheMapThenTheOperationDefault() {
        RateLimitingInstrumentation instrumentation =
                new RateLimitingInstrumentation(rateLimitService, subjectResolver, properties);
        assertThat(instrumentation.policyFor("a", OperationDefinition.Operation.QUERY, Tier.AUTHOR))
                .isEqualTo("graphql-read");
        assertThat(instrumentation.policyFor("m", OperationDefinition.Operation.MUTATION, Tier.GUEST))
                .isEqualTo("graphql-write");
        assertThat(instrumentation.policyFor("special", OperationDefinition.Operation.MUTATION, Tier.ADMIN))
                .isEqualTo("special-policy");
        assertThat(instrumentation.policyFor("special", OperationDefinition.Operation.QUERY, Tier.SERVICE))
                .isEqualTo("service");
    }

    @Test
    void aRejectedFieldThrowsRateLimitExceededAndItsFetcherNeverRuns() {
        when(rateLimitService.tryConsume(eq("graphql-read"), any()))
                .thenReturn(Decision.rejected(TimeUnit.SECONDS.toNanos(7), 240));

        ExecutionResult result = execute("{ a }", PLAYER);

        assertThat(fetcherExceptions).singleElement().isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
            assertThat(e.getPolicy()).isEqualTo("graphql-read");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(7);
            assertThat(e.getLimit()).isEqualTo(240);
        });
        Map<String, Object> data = result.getData();
        assertThat(data).containsEntry("a", null);
    }

    @Test
    void aFailClosedPolicyWithRedisDownThrowsLimiterUnavailable() {
        when(rateLimitService.tryConsume(eq("graphql-write"), any())).thenReturn(Decision.unavailable());

        execute("mutation { m }", PLAYER);

        assertThat(fetcherExceptions).singleElement().isInstanceOf(LimiterUnavailableException.class);
    }

    @Test
    void withoutAContextSubjectTheCurrentThreadsSubjectIsCharged() {
        when(subjectResolver.current()).thenReturn(PLAYER);

        execute("{ a }", null);

        verify(rateLimitService).tryConsume("graphql-read", PLAYER);
    }

    @Test
    void theLimiterSwitchedOffLeavesFetchersUnwrapped() {
        when(rateLimitService.isEnabled()).thenReturn(false);

        ExecutionResult result = execute("{ a b { c } } ", PLAYER);

        assertThat(result.getErrors()).isEmpty();
        verify(rateLimitService, never()).tryConsume(anyString(), any());
    }
}
