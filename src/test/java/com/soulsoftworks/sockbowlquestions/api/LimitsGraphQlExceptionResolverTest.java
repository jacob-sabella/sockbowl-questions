package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.ratelimit.Decision;
import com.soulsoftworks.sockbowlquestions.ratelimit.IpBannedException;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimiterUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBannedException;
import graphql.GraphQLError;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLObjectType;
import graphql.Scalars;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-Q1: a limit exception thrown from a GraphQL data fetcher becomes a field
 * error whose classification is the matching {@link SockbowlErrorType} and whose
 * extensions carry the same fields as the REST JSON body (minus {@code message}).
 */
class LimitsGraphQlExceptionResolverTest {

    private final LimitsGraphQlExceptionResolver resolver = new LimitsGraphQlExceptionResolver();

    private static DataFetchingEnvironment env() {
        Field field = Field.newField("generateAndAddTossup").sourceLocation(new SourceLocation(1, 3)).build();
        ExecutionStepInfo step = ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("generateAndAddTossup"))
                .build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .mergedField(graphql.execution.MergedField.newMergedField(field).build())
                .executionStepInfo(step)
                .parentType(GraphQLObjectType.newObject().name("Mutation").build())
                .build();
    }

    private GraphQLError resolve(Throwable ex) throws Exception {
        List<GraphQLError> errors = resolver.resolveException(ex, env()).block();
        assertThat(errors).as("resolved errors").hasSize(1);
        return errors.get(0);
    }

    @Test
    void rateLimitedCarriesPolicyRetryAfterAndLimit() throws Exception {
        long retryNanos = TimeUnit.SECONDS.toNanos(42);
        GraphQLError error = resolve(RateLimitExceededException.from("graphql-write", Decision.rejected(retryNanos, 60)));

        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.RATE_LIMITED);
        assertThat(error.getPath()).containsExactly("generateAndAddTossup");
        assertThat(error.getExtensions())
                .containsEntry("error", "rate_limited")
                .containsEntry("policy", "graphql-write")
                .containsEntry("retryAfterSeconds", 42L)
                .doesNotContainKey("message");
        // The spec form (what ng receives) carries the classification.
        assertThat(error.toSpecification().get("extensions"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("classification", "RATE_LIMITED")
                .containsEntry("error", "rate_limited");
        assertThat(error.getMessage()).isNotBlank();
    }

    @Test
    void quotaExceededCarriesMetricLimitUsedAndResetsAt() throws Exception {
        Instant resetsAt = Instant.parse("2026-09-28T00:00:00Z");
        GraphQLError error = resolve(new QuotaExceededException("ai.generations", 20, 20, resetsAt,
                Instant.parse("2026-09-27T12:00:00Z")));

        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.QUOTA_EXCEEDED);
        assertThat(error.getExtensions())
                .containsEntry("error", "quota_exceeded")
                .containsEntry("metric", "ai.generations")
                .containsEntry("limit", 20L)
                .containsEntry("used", 20L)
                .containsEntry("resetsAt", "2026-09-28T00:00:00Z");
    }

    @Test
    void aPermanentQuotaKeepsANullResetsAt() throws Exception {
        GraphQLError error = resolve(new QuotaExceededException("packets-owned", 300, 300, null));
        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.QUOTA_EXCEEDED);
        assertThat(error.getExtensions()).containsKey("resetsAt");
        assertThat(error.getExtensions().get("resetsAt")).isNull();
    }

    @Test
    void subjectAndIpBansAreBanned() throws Exception {
        GraphQLError subject = resolve(new SubjectBannedException("spam", Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(subject.getErrorType()).isEqualTo(SockbowlErrorType.BANNED);
        assertThat(subject.getExtensions()).containsEntry("error", "banned").containsEntry("reason", "spam");

        GraphQLError ip = resolve(new IpBannedException(null));
        assertThat(ip.getErrorType()).isEqualTo(SockbowlErrorType.BANNED);
        assertThat(ip.getExtensions()).containsEntry("error", "ip_banned");
    }

    @Test
    void limiterUnavailableIsItsOwnClassification() throws Exception {
        GraphQLError error = resolve(new LimiterUnavailableException("ai-generate"));
        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.LIMITER_UNAVAILABLE);
        assertThat(error.getExtensions())
                .containsEntry("error", "limiter_unavailable")
                .containsEntry("policy", "ai-generate");
    }

    @Test
    void aWrappedLimitExceptionIsFound() throws Exception {
        RateLimitExceededException cause = new RateLimitExceededException("graphql-read", 5, 240);
        GraphQLError error = resolve(new CompletionException(new RuntimeException("wrapper", cause)));
        assertThat(error.getErrorType()).isEqualTo(SockbowlErrorType.RATE_LIMITED);
        assertThat(error.getExtensions()).containsEntry("policy", "graphql-read");
    }

    @Test
    void otherExceptionsAreLeftToTheNextResolver() {
        assertThat(resolver.resolveException(new IllegalStateException("boom"), env()).block()).isNull();
        assertThat(LimitsGraphQlExceptionResolver.findLimitException(new RuntimeException())).isNull();
    }
}
