package com.soulsoftworks.sockbowlquestions.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit rules of {@link RequestGuardFilter} (plan m4-limits section 2.2): check
 * order, route/fallback charging, the fail-closed 503, bans, exemptions and the
 * usage touch, with the limiter and checkers mocked.
 */
class RequestGuardFilterTest {

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final RateLimitEventRecorder recorder = mock(RateLimitEventRecorder.class);
    // The filter calls the interfaces' default ensureNotBanned(), which delegates to findActiveBan().
    private final IpBanChecker ipBanChecker = mock(IpBanChecker.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final SubjectBanChecker subjectBanChecker =
            mock(SubjectBanChecker.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final UsageTouchTracker usageTouchTracker = mock(UsageTouchTracker.class);
    private RateLimitProperties properties;
    private RequestGuardFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setServiceClients(List.of("sockbowl-game-backend"));
        RateLimitProperties.Route create = new RateLimitProperties.Route();
        create.setMethod("POST");
        create.setPattern("/api/v1/session/create-new-game-session");
        create.setPolicies(List.of("session-create"));
        RateLimitProperties.Route admin = new RateLimitProperties.Route();
        admin.setPattern("/api/v1/admin/**");
        admin.setPolicies(List.of("admin"));
        properties.setRoutes(List.of(create, admin));

        ClientIpResolver ipResolver = new ClientIpResolver();
        LimitSubjectResolver subjectResolver = new LimitSubjectResolver(ipResolver, properties, "sockbowl-game-backend");
        filter = new RequestGuardFilter(rateLimitService, properties, subjectResolver, ipResolver,
                ipBanChecker, subjectBanChecker, recorder, usageTouchTracker);

        when(rateLimitService.isEnabled()).thenReturn(true);
        when(rateLimitService.tryConsume(anyString(), any())).thenReturn(Decision.allowed(9, 10));
        when(ipBanChecker.findActiveBan(anyString())).thenReturn(Optional.empty());
        when(subjectBanChecker.findActiveBan(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.10");
        return request;
    }

    private static void authenticate(String sub, String azp, String... roles) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub).claim("azp", azp)
                .claim("realm_access", Map.of("roles", List.of(roles))).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private MockFilterChain run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    @Test
    void routePoliciesAreChargedBeforeTheDefault() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(request("POST", "/api/v1/session/create-new-game-session"), response);

        var order = inOrder(rateLimitService);
        order.verify(rateLimitService).tryConsume(eq("session-create"), any());
        order.verify(rateLimitService).tryConsume(eq("default"), any());
        verify(rateLimitService, never()).tryConsume(eq("service"), any());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void routeMethodMustMatch() {
        assertThat(filter.policiesFor("GET", "/api/v1/session/create-new-game-session", LimitSubject.guest("1.2.3.4")))
                .containsExactly("default");
        assertThat(filter.policiesFor("POST", "/api/v1/session/create-new-game-session", LimitSubject.guest("1.2.3.4")))
                .containsExactly("session-create", "default");
        assertThat(filter.policiesFor("DELETE", "/api/v1/admin/bans/x", LimitSubject.guest("1.2.3.4")))
                .containsExactly("admin", "default");
        assertThat(filter.policiesFor("GET", "/api/v1/admin", LimitSubject.guest("1.2.3.4")))
                .containsExactly("admin", "default");
    }

    @Test
    void aRouteCanOptOutOfTheFallbackPolicy() {
        RateLimitProperties.Route graphql = new RateLimitProperties.Route();
        graphql.setMethod("POST");
        graphql.setPattern("/graphql");
        graphql.setPolicies(List.of("graphql-http"));
        graphql.setFallback(false);
        List<RateLimitProperties.Route> routes = new java.util.ArrayList<>(properties.getRoutes());
        routes.add(graphql);
        properties.setRoutes(routes);

        assertThat(filter.policiesFor("POST", "/graphql", LimitSubject.guest("1.2.3.4")))
                .containsExactly("graphql-http");
        assertThat(filter.policiesFor("POST", "/graphql", new LimitSubject("svc", "1.2.3.4", Tier.SERVICE)))
                .containsExactly("graphql-http");
        assertThat(filter.policiesFor("GET", "/graphql", LimitSubject.guest("1.2.3.4")))
                .as("the route is POST-only, so a GET falls back").containsExactly("default");
    }

    @Test
    void serviceTierIsChargedServiceNeverDefault() throws Exception {
        authenticate("svc", "sockbowl-game-backend");
        run(request("GET", "/api/v1/auth/status"), new MockHttpServletResponse());

        verify(rateLimitService).tryConsume(eq("service"), any());
        verify(rateLimitService, never()).tryConsume(eq("default"), any());
        assertThat(filter.policiesFor("GET", "/x", new LimitSubject("svc", "1.2.3.4", Tier.SERVICE)))
                .containsExactly("service");
    }

    @Test
    void aRejectedRoutePolicyStopsBeforeTheDefaultAndWrites429() throws Exception {
        when(rateLimitService.tryConsume(eq("session-create"), any()))
                .thenReturn(Decision.rejected(TimeUnit.SECONDS.toNanos(37), 3));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("POST", "/api/v1/session/create-new-game-session"), response, chain);

        verify(rateLimitService, never()).tryConsume(eq("default"), any());
        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("X-RateLimit-Policy")).isEqualTo("session-create");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"error\":\"rate_limited\",\"policy\":\"session-create\",\"retryAfterSeconds\":37,"
                        + "\"message\":\"Too many requests\"}");
        verify(recorder).record(eq("session-create"), eq(RateLimitEventRecorder.KIND_RATE), any(),
                eq("/api/v1/session/create-new-game-session"));
        verifyNoInteractions(usageTouchTracker);
    }

    @Test
    void failClosedPolicyWithRedisDownIs503() throws Exception {
        when(rateLimitService.tryConsume(eq("default"), any())).thenReturn(Decision.unavailable());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("GET", "/api/v1/auth/status"), response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"limiter_unavailable\",\"policy\":\"default\"}");
    }

    @Test
    void failedOpenDecisionLetsTheRequestThroughWithoutHeaders() throws Exception {
        when(rateLimitService.tryConsume(anyString(), any())).thenReturn(Decision.failedOpen(120));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(request("GET", "/api/v1/auth/status"), response);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Policy")).isNull();
    }

    @Test
    void successHeadersReportTheMostConstrainedPolicy() throws Exception {
        when(rateLimitService.tryConsume(eq("session-create"), any())).thenReturn(Decision.allowed(1, 3));
        when(rateLimitService.tryConsume(eq("default"), any())).thenReturn(Decision.allowed(118, 120));
        MockHttpServletResponse response = new MockHttpServletResponse();
        run(request("POST", "/api/v1/session/create-new-game-session"), response);

        assertThat(response.getHeader("X-RateLimit-Policy")).isEqualTo("session-create");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    void ipBanIsCheckedFirstOnTheRawAddressAndWrites403() throws Exception {
        Instant expires = Instant.parse("2026-10-01T00:00:00Z");
        when(ipBanChecker.findActiveBan("192.0.2.10")).thenReturn(Optional.of(expires));
        authenticate("kc-alice", "sockbowl-game", "player");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("GET", "/api/v1/auth/status"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"ip_banned\",\"expiresAt\":\"2026-10-01T00:00:00Z\"}");
        verifyNoInteractions(chain, subjectBanChecker, usageTouchTracker);
        verify(rateLimitService, never()).tryConsume(anyString(), any());
        verify(recorder).record(eq("ip-ban"), eq(RateLimitEventRecorder.KIND_BAN), any(), any());
    }

    @Test
    void bannedSubjectGets403BeforeAnyTokenIsSpent() throws Exception {
        when(subjectBanChecker.findActiveBan("kc-bad"))
                .thenReturn(Optional.of(new SubjectBanChecker.SubjectBan("spam", null)));
        authenticate("kc-bad", "sockbowl-game", "player");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/auth/status"), response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"banned\",\"reason\":\"spam\",\"expiresAt\":null}");
        verify(rateLimitService, never()).tryConsume(anyString(), any());
    }

    @Test
    void aDeferredPathSkipsOnlyTheSubjectBanStepAndNothingElse() throws Exception {
        when(subjectBanChecker.findActiveBan("kc-bad"))
                .thenReturn(Optional.of(new SubjectBanChecker.SubjectBan("spam", null)));
        authenticate("kc-bad", "sockbowl-ng", "player");

        // Without a deferral the GraphQL path is a plain 403 like any other path.
        MockHttpServletResponse before = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/graphql"), before, mock(FilterChain.class));
        assertThat(before.getStatus()).isEqualTo(403);

        filter.deferSubjectBanChecks(List.of("/graphql"));
        assertThat(filter.isSubjectBanDeferred("/graphql")).isTrue();
        assertThat(filter.isSubjectBanDeferred("/api/qbreader/count")).isFalse();

        // Deferred: passed on (the GraphQL interceptor answers BANNED), still charged and IP-checked.
        MockFilterChain chain = run(request("POST", "/graphql"), new MockHttpServletResponse());
        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimitService).tryConsume(eq("default"), any());
        verify(ipBanChecker, org.mockito.Mockito.times(2)).findActiveBan("192.0.2.10");

        // Every other path is still rejected here.
        MockHttpServletResponse rest = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/auth/status"), rest, mock(FilterChain.class));
        assertThat(rest.getStatus()).isEqualTo(403);

        // An IP ban is never deferred.
        when(ipBanChecker.findActiveBan("192.0.2.10")).thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        MockHttpServletResponse ipBanned = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/graphql"), ipBanned, mock(FilterChain.class));
        assertThat(ipBanned.getStatus()).isEqualTo(403);
        assertThat(ipBanned.getContentAsString()).contains("ip_banned");
    }

    @Test
    void guestsAreNeverSubjectBanChecked() throws Exception {
        run(request("GET", "/api/v1/auth/status"), new MockHttpServletResponse());
        verify(subjectBanChecker, never()).findActiveBan(any());
        verify(ipBanChecker).findActiveBan("192.0.2.10");
    }

    @Test
    void limiterDisabledStillEnforcesBansButChargesNothing() throws Exception {
        when(rateLimitService.isEnabled()).thenReturn(false);
        MockFilterChain chain = run(request("GET", "/api/v1/auth/status"), new MockHttpServletResponse());
        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimitService, never()).tryConsume(anyString(), any());
        verify(ipBanChecker).findActiveBan("192.0.2.10");

        when(ipBanChecker.findActiveBan("192.0.2.10")).thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        MockHttpServletResponse banned = new MockHttpServletResponse();
        run(request("GET", "/api/v1/auth/status"), banned);
        assertThat(banned.getStatus()).isEqualTo(403);
    }

    @Test
    void exemptPathsAndPreflightsBypassEverything() throws Exception {
        properties.setExempt(List.of("/actuator/health", "/actuator/health/**"));
        run(request("GET", "/actuator/health"), new MockHttpServletResponse());
        run(request("GET", "/actuator/health/liveness"), new MockHttpServletResponse());

        MockHttpServletRequest preflight = request("OPTIONS", "/api/v1/session/create-new-game-session");
        preflight.addHeader("Origin", "http://localhost");
        preflight.addHeader("Access-Control-Request-Method", "POST");
        run(preflight, new MockHttpServletResponse());

        verifyNoInteractions(ipBanChecker, subjectBanChecker, usageTouchTracker);
        verify(rateLimitService, never()).tryConsume(anyString(), any());
    }

    @Test
    void authenticatedAdmittedRequestsAreTouchedGuestsAreNot() throws Exception {
        run(request("GET", "/api/v1/auth/status"), new MockHttpServletResponse());
        verifyNoInteractions(usageTouchTracker);

        authenticate("kc-author", "sockbowl-game", "author");
        run(request("GET", "/api/v1/auth/status"), new MockHttpServletResponse());
        verify(usageTouchTracker).touch(new LimitSubject("kc-author", "192.0.2.10", Tier.AUTHOR));
    }

    @Test
    void aFailingUsageTouchNeverFailsTheRequest() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(usageTouchTracker).touch(any());
        authenticate("kc-author", "sockbowl-game", "author");
        MockFilterChain chain = run(request("GET", "/api/v1/auth/status"), new MockHttpServletResponse());
        assertThat(chain.getRequest()).isNotNull();
    }
}
