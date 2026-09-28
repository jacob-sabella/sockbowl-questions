package com.soulsoftworks.sockbowlquestions.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The REST request guard (plan m4-limits section 2.2, WP-G2, M4-RL-03).
 *
 * <p>One instance is added to <b>both</b> security chains ({@code SecurityConfig}
 * and {@code NoSecurityConfig}) just before {@code AuthorizationFilter}, so it
 * runs after bearer authentication (the {@code sub} and tier are known) and
 * before any authorization decision or controller. {@link RequestGuardFilterConfig}
 * disables Boot's automatic servlet registration of this bean so it never runs
 * a second time outside the security chain.
 *
 * <p>Per request, in order:
 * <ol>
 *   <li>CORS preflights and {@code sockbowl.ratelimit.exempt} paths
 *       ({@code /actuator/health/**}) pass untouched.</li>
 *   <li>{@link IpBanChecker} on the raw remote address (403 {@code ip_banned}).</li>
 *   <li>{@link SubjectBanChecker} for an authenticated subject (403 {@code banned}),
 *       except on the paths a present {@link SubjectBanDeferral} enforces itself
 *       ({@code POST /graphql}: a GraphQL {@code BANNED} error, WP-Q4).</li>
 *   <li>When {@code sockbowl.ratelimit.enabled}: every matching
 *       {@code sockbowl.ratelimit.routes} policy is charged in order (all must
 *       pass), then the fallback: {@code service} for the SERVICE tier,
 *       otherwise {@code default}, unless a matching route sets
 *       {@code fallback=false}. A request is never charged both
 *       {@code default} and {@code service}, and a rejected route policy stops
 *       the chain before the fallback is charged.</li>
 *   <li>{@link UsageTouchTracker#touch} for an authenticated subject.</li>
 * </ol>
 *
 * <p>A rejection is written directly as the section 2.1 JSON body through
 * {@link LimitErrorResponses} (429 with {@code Retry-After} and
 * {@code X-RateLimit-*}, 503 {@code limiter_unavailable} for a fail-closed
 * policy with Redis down, 403 for bans) and recorded, sampled, to
 * {@code rl:events}. An admitted request carries {@code X-RateLimit-Limit},
 * {@code X-RateLimit-Remaining} and {@code X-RateLimit-Policy} for its most
 * constrained charged policy.
 *
 * <p>Known, intentional gap: a request with an <i>invalid</i> bearer is
 * rejected with 401 by the resource-server filter before it reaches this
 * guard, so it is not rate limited here (the failed decode is the cost).
 */
@Slf4j
public class RequestGuardFilter extends OncePerRequestFilter {

    public static final String DEFAULT_POLICY = "default";
    public static final String SERVICE_POLICY = "service";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final LimitSubjectResolver subjectResolver;
    private final ClientIpResolver clientIpResolver;
    private final IpBanChecker ipBanChecker;
    private final SubjectBanChecker subjectBanChecker;
    private final RateLimitEventRecorder eventRecorder;
    private final UsageTouchTracker usageTouchTracker;
    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();
    private volatile List<String> subjectBanDeferredPaths = List.of();

    public RequestGuardFilter(RateLimitService rateLimitService,
                              RateLimitProperties properties,
                              LimitSubjectResolver subjectResolver,
                              ClientIpResolver clientIpResolver,
                              IpBanChecker ipBanChecker,
                              SubjectBanChecker subjectBanChecker,
                              RateLimitEventRecorder eventRecorder,
                              UsageTouchTracker usageTouchTracker) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.subjectResolver = subjectResolver;
        this.clientIpResolver = clientIpResolver;
        this.ipBanChecker = ipBanChecker;
        this.subjectBanChecker = subjectBanChecker;
        this.eventRecorder = eventRecorder;
        this.usageTouchTracker = usageTouchTracker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return CorsUtils.isPreFlightRequest(request) || isExempt(pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathOf(request);
        LimitSubject subject = subjectResolver.resolve(request);
        try {
            ipBanChecker.ensureNotBanned(clientIpResolver.rawAddress(request));
            if (subject.isAuthenticated() && !isSubjectBanDeferred(path)) {
                subjectBanChecker.ensureNotBanned(subject.sub());
            }
        } catch (LimitException ban) {
            eventRecorder.record(ban instanceof IpBannedException ? "ip-ban" : "ban",
                    RateLimitEventRecorder.KIND_BAN, subject, path);
            LimitErrorResponses.write(response, ban);
            return;
        }

        if (rateLimitService.isEnabled()) {
            Charged tightest = null;
            for (String policy : policiesFor(request.getMethod(), path, subject)) {
                Decision decision = rateLimitService.tryConsume(policy, subject);
                if (decision.limiterUnavailable()) {
                    eventRecorder.record(policy, RateLimitEventRecorder.KIND_RATE, subject, path);
                    LimitErrorResponses.write(response, new LimiterUnavailableException(policy));
                    return;
                }
                if (!decision.allowed()) {
                    eventRecorder.record(policy, RateLimitEventRecorder.KIND_RATE, subject, path);
                    LimitErrorResponses.write(response, RateLimitExceededException.from(policy, decision));
                    return;
                }
                tightest = Charged.tighter(tightest, new Charged(policy, decision));
            }
            if (tightest != null) {
                tightest.addHeaders(response);
            }
        }

        if (subject.isAuthenticated()) {
            touch(subject);
        }
        chain.doFilter(request, response);
    }

    /**
     * The policies charged for a request, in order: every matching route's
     * policies, then {@code service} (SERVICE tier) or {@code default} unless a
     * matching route opts out of the fallback.
     */
    List<String> policiesFor(String method, String path, LimitSubject subject) {
        List<String> policies = new ArrayList<>();
        boolean chargeFallback = true;
        for (RateLimitProperties.Route route : properties.getRoutes()) {
            if (matches(route, method, path)) {
                chargeFallback &= route.isFallback();
                for (String policy : route.getPolicies()) {
                    if (!policies.contains(policy)) {
                        policies.add(policy);
                    }
                }
            }
        }
        String fallback = subject.tier() == Tier.SERVICE ? SERVICE_POLICY : DEFAULT_POLICY;
        policies.remove(DEFAULT_POLICY);
        policies.remove(SERVICE_POLICY);
        if (chargeFallback) {
            policies.add(fallback);
        }
        return policies;
    }

    private boolean matches(RateLimitProperties.Route route, String method, String path) {
        if (route.getPattern() == null) {
            return false;
        }
        if (route.getMethod() != null && !route.getMethod().isBlank()
                && !route.getMethod().trim().equalsIgnoreCase(method)) {
            return false;
        }
        return pathMatcher.match(route.getPattern(), path);
    }

    /**
     * Declares paths whose subject-ban check a {@link SubjectBanDeferral} performs
     * itself. Called by {@link RequestGuardFilterConfig} only with the patterns of
     * deferral beans that exist.
     */
    public void deferSubjectBanChecks(Collection<String> patterns) {
        List<String> merged = new ArrayList<>(subjectBanDeferredPaths);
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && !merged.contains(pattern)) {
                merged.add(pattern);
            }
        }
        subjectBanDeferredPaths = List.copyOf(merged);
    }

    boolean isSubjectBanDeferred(String path) {
        for (String pattern : subjectBanDeferredPaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExempt(String path) {
        for (String pattern : properties.getExempt()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private String pathOf(HttpServletRequest request) {
        return urlPathHelper.getPathWithinApplication(request);
    }

    private void touch(LimitSubject subject) {
        try {
            usageTouchTracker.touch(subject);
        } catch (RuntimeException e) {
            log.debug("Usage touch failed for {}: {}", subject.sub(), e.toString());
        }
    }

    /** A consumed policy and its decision; the "tightest" one is reported in the success headers. */
    private record Charged(String policy, Decision decision) {

        static Charged tighter(Charged current, Charged candidate) {
            if (candidate.decision.remaining() < 0 || candidate.decision.limit() < 0) {
                return current;
            }
            if (current == null || candidate.decision.remaining() < current.decision.remaining()) {
                return candidate;
            }
            return current;
        }

        void addHeaders(HttpServletResponse response) {
            response.setHeader(LimitErrorResponses.X_RATE_LIMIT_LIMIT, Long.toString(decision.limit()));
            response.setHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING, Long.toString(decision.remaining()));
            response.setHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY, policy);
        }
    }
}
