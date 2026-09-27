package com.soulsoftworks.sockbowlquestions.ratelimit.graphql;

import com.soulsoftworks.sockbowlquestions.ratelimit.ClientIpResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Resolves the {@link LimitSubject} of a {@code POST /graphql} request once, on the
 * request thread, and stores it in the {@link GraphQLContext} under
 * {@link #SUBJECT_KEY}, so field-level limiting (WP-Q2's instrumentation) and any
 * data fetcher can charge the caller without a servlet request or a security
 * context bound to their thread.
 *
 * <p>The subject is the same one {@code RequestGuardFilter} charges: the security
 * context's authentication plus the request's remote address, normalized by
 * {@link ClientIpResolver} (so {@code X-Forwarded-For} is never trusted here
 * either).
 */
@Component
public class LimitSubjectGraphQlInterceptor implements WebGraphQlInterceptor {

    /** {@link GraphQLContext} key of the request's {@link LimitSubject}. */
    public static final String SUBJECT_KEY = LimitSubject.class.getName();

    private final LimitSubjectResolver subjectResolver;

    public LimitSubjectGraphQlInterceptor(LimitSubjectResolver subjectResolver) {
        this.subjectResolver = subjectResolver;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        LimitSubject subject = subjectResolver.resolve(
                SecurityContextHolder.getContext().getAuthentication(), ipOf(request.getRemoteAddress()));
        request.configureExecutionInput((input, builder) -> {
            input.getGraphQLContext().put(SUBJECT_KEY, subject);
            return input;
        });
        return chain.next(request);
    }

    /**
     * The subject stored for this request, or {@code null} when the operation did
     * not come through the web transport (e.g. a direct {@code ExecutionGraphQlService} call).
     */
    public static LimitSubject subjectOf(GraphQLContext context) {
        return context == null ? null : context.get(SUBJECT_KEY);
    }

    /** {@link #subjectOf(GraphQLContext)} for a data fetcher. */
    public static LimitSubject subjectOf(DataFetchingEnvironment env) {
        return env == null ? null : subjectOf(env.getGraphQlContext());
    }

    private static String ipOf(InetSocketAddress address) {
        if (address == null) {
            return ClientIpResolver.UNKNOWN;
        }
        if (address.getAddress() != null) {
            return ClientIpResolver.normalize(address.getAddress());
        }
        return ClientIpResolver.normalize(address.getHostString());
    }
}
