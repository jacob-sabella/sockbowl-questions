package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.ratelimit.IpBannedException;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitException;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimiterUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBannedException;
import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the M4 limit exceptions thrown inside a GraphQL data fetcher (a field-level
 * rate limit, a quota, the AI guard, a ban) to a field error whose
 * {@code extensions.classification} is a {@link SockbowlErrorType} (plan m4-limits
 * section 2.1). The request itself still answers HTTP 200; only the coarse
 * {@code graphql-http} REST policy produces a real 429.
 *
 * <table>
 *   <caption>Classification and extensions</caption>
 *   <tr><th>Exception</th><th>classification</th><th>extensions</th></tr>
 *   <tr><td>{@link RateLimitExceededException}</td><td>RATE_LIMITED</td>
 *       <td>error=rate_limited, policy, retryAfterSeconds</td></tr>
 *   <tr><td>{@link QuotaExceededException}</td><td>QUOTA_EXCEEDED</td>
 *       <td>error=quota_exceeded, metric, limit, used, resetsAt (ISO string or null)</td></tr>
 *   <tr><td>{@link SubjectBannedException}</td><td>BANNED</td>
 *       <td>error=banned, reason, expiresAt</td></tr>
 *   <tr><td>{@link IpBannedException}</td><td>BANNED</td><td>error=ip_banned, expiresAt</td></tr>
 *   <tr><td>{@link LimiterUnavailableException}</td><td>LIMITER_UNAVAILABLE</td>
 *       <td>error=limiter_unavailable, policy</td></tr>
 * </table>
 *
 * The extension fields are exactly the HTTP JSON body's fields (minus its
 * human-readable {@code message}), so ng parses both with one
 * {@code limitErrorFrom}. The exception is also found when it is wrapped (for
 * example in a {@code CompletionException}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LimitsGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        LimitException limit = findLimitException(ex);
        if (limit == null) {
            return null;
        }
        return GraphqlErrorBuilder.newError(env)
                .errorType(classify(limit))
                .message(limit.getMessage())
                .extensions(extensionsOf(limit))
                .build();
    }

    /** The {@link SockbowlErrorType} of a limit exception. */
    public static ErrorClassification classify(LimitException ex) {
        if (ex instanceof RateLimitExceededException) {
            return SockbowlErrorType.RATE_LIMITED;
        }
        if (ex instanceof QuotaExceededException) {
            return SockbowlErrorType.QUOTA_EXCEEDED;
        }
        if (ex instanceof SubjectBannedException || ex instanceof IpBannedException) {
            return SockbowlErrorType.BANNED;
        }
        if (ex instanceof LimiterUnavailableException) {
            return SockbowlErrorType.LIMITER_UNAVAILABLE;
        }
        return SockbowlErrorType.RATE_LIMITED;
    }

    /** The HTTP body fields of the exception, minus {@code message}; {@code null} values are kept. */
    public static Map<String, Object> extensionsOf(LimitException ex) {
        Map<String, Object> extensions = new LinkedHashMap<>(ex.body());
        extensions.remove("message");
        return extensions;
    }

    static LimitException findLimitException(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof LimitException limit) {
                return limit;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
