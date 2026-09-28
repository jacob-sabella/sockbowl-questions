package com.soulsoftworks.sockbowlquestions.ban;

import com.soulsoftworks.sockbowlquestions.api.LimitsGraphQlExceptionResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.ClientIpResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBanChecker;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBanDeferral;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBannedException;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Enforces subject bans (D8, M4-AB-01) on the GraphQL endpoint before any field
 * runs. A banned caller gets HTTP 200 with a single request-level error whose
 * {@code extensions.classification} is {@code BANNED} and whose other extensions
 * mirror the REST 403 body ({@code error=banned, reason, expiresAt}), and no
 * {@code data} (plan m4-limits section 2.1: GraphQL keeps HTTP 200 and reports
 * limits as classified errors).
 *
 * <p>As a {@link SubjectBanDeferral}, its presence is what makes
 * {@code RequestGuardFilter} skip the subject-ban step for the GraphQL path; IP
 * bans and the {@code graphql-http} rate policy still answer at the HTTP level.
 * Every web GraphQL request passes through this interceptor, so the check can't
 * be skipped by a different operation shape (queries, mutations, batches of
 * aliases, introspection).
 */
@Component
public class GraphQlBanInterceptor implements WebGraphQlInterceptor, SubjectBanDeferral, Ordered {

    /** Runs before every other interceptor, so nothing is resolved or charged for a banned caller. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final SubjectBanChecker subjectBanChecker;
    private final LimitSubjectResolver subjectResolver;
    private final RateLimitEventRecorder eventRecorder;
    private final String graphQlPath;

    public GraphQlBanInterceptor(SubjectBanChecker subjectBanChecker,
                                 LimitSubjectResolver subjectResolver,
                                 RateLimitEventRecorder eventRecorder,
                                 @Value("${spring.graphql.http.path:/graphql}") String graphQlPath) {
        this.subjectBanChecker = subjectBanChecker;
        this.subjectResolver = subjectResolver;
        this.eventRecorder = eventRecorder;
        this.graphQlPath = graphQlPath;
    }

    @Override
    public List<String> deferredPaths() {
        return List.of(graphQlPath);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        LimitSubject subject = subjectResolver.resolve(
                SecurityContextHolder.getContext().getAuthentication(), ipOf(request.getRemoteAddress()));
        if (subject.isAuthenticated()) {
            try {
                subjectBanChecker.ensureNotBanned(subject.sub());
            } catch (SubjectBannedException banned) {
                eventRecorder.record("ban", RateLimitEventRecorder.KIND_BAN, subject,
                        request.getUri() == null ? graphQlPath : request.getUri().getPath());
                return Mono.just(bannedResponse(request.toExecutionInput(), banned));
            }
        }
        return chain.next(request);
    }

    private static String ipOf(InetSocketAddress address) {
        if (address == null) {
            return ClientIpResolver.UNKNOWN;
        }
        return address.getAddress() != null
                ? ClientIpResolver.normalize(address.getAddress())
                : ClientIpResolver.normalize(address.getHostString());
    }

    static WebGraphQlResponse bannedResponse(ExecutionInput input, SubjectBannedException banned) {
        GraphQLError error = GraphqlErrorBuilder.newError()
                .errorType(LimitsGraphQlExceptionResolver.classify(banned))
                .message(banned.getMessage())
                .extensions(LimitsGraphQlExceptionResolver.extensionsOf(banned))
                .build();
        ExecutionResult result = ExecutionResultImpl.newExecutionResult().addError(error).build();
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(input, result));
    }
}
