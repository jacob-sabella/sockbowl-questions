package com.soulsoftworks.sockbowlquestions.ratelimit;

import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WP-Q1 acceptance (M4-RL-02, the REST half of M4-RL-04): through the real
 * auth-on security chain, a real Redis and the shipped policies, each questions
 * REST policy trips at its configured size and recovers when the clock advances
 * by its refill period, and the coarse {@code graphql-http} policy answers a real
 * HTTP 429 on {@code POST /graphql}.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(QuestionsRequestGuardITSupport.ClockConfig.class)
class QuestionsRequestGuardFilterIT extends QuestionsRequestGuardITSupport {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    FilterChainProxy filterChainProxy;

    static RequestPostProcessor userToken(String sub, String azp, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", azp)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    static RequestPostProcessor player(String sub) {
        return userToken(sub, "sockbowl-ng", "player", "packet:read");
    }

    static RequestPostProcessor serviceToken() {
        return userToken("service-account-sockbowl-game-backend", "sockbowl-game-backend", "packet:read");
    }

    private MvcResult importRandom(RequestPostProcessor caller, String ip) throws Exception {
        return mvc.perform(post(IMPORT_RANDOM).with(caller).with(from(ip)).contentType(json()).content(importBody()))
                .andReturn();
    }

    private MvcResult graphQl(RequestPostProcessor caller, String ip) throws Exception {
        return mvc.perform(post(GRAPHQL).with(caller).with(from(ip)).contentType(json()).content(graphQlBody()))
                .andReturn();
    }

    /* ------------------------------------------------------------------ */
    /* Wiring                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    void guardRunsAfterBearerAuthAndBeforeAuthorization() {
        List<Filter> filters = filterChainProxy.getFilterChains().get(0).getFilters();
        int bearer = indexOf(filters, BearerTokenAuthenticationFilter.class);
        int guard = indexOf(filters, RequestGuardFilter.class);
        int authorization = indexOf(filters, AuthorizationFilter.class);
        assertThat(bearer).isNotNegative();
        assertThat(guard).isGreaterThan(bearer).isLessThan(authorization);
    }

    static int indexOf(List<Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /* ------------------------------------------------------------------ */
    /* bank-read                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void bankReadTripsOnTheThirtyFirstCallAndRecoversAfterAMinute() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 30; i++) {
            // Alternate the GET and POST bank routes: they share one bank-read bucket.
            MvcResult ok = i % 2 == 0
                    ? mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn()
                    : mvc.perform(post(COUNT).with(from(ip)).contentType(json()).content("{}")).andReturn();
            assertThat(ok.getResponse().getStatus()).as("bank read #%d", i).isEqualTo(200);
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("bank-read");
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING))
                    .isEqualTo(Integer.toString(30 - i));
        }
        MvcResult limited = mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn();
        assertRateLimited(limited, "bank-read", 30);
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(1L, Duration.ofMinutes(1).toSeconds());

        // Another address is unaffected.
        assertThat(mvc.perform(get(CATEGORY_COUNTS).with(from(nextIp()))).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void aRejectionIsRecordedToTheEventStreamAsQuestions() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 30; i++) {
            mvc.perform(get(CATEGORY_COUNTS).with(from(ip)));
        }
        assertRateLimited(mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn(), "bank-read", 30);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<StreamMessage<String, String>> events =
                    rateLimitRedis.sync().xrange(UsageKeys.events(), Range.create("-", "+"));
            assertThat(events).anySatisfy(e -> assertThat(e.getBody())
                    .containsEntry("policy", "bank-read")
                    .containsEntry("kind", "rate")
                    .containsEntry("svc", "questions")
                    .containsEntry("ip", ip)
                    .containsEntry("path", CATEGORY_COUNTS));
        });
    }

    /* ------------------------------------------------------------------ */
    /* import-random: import (per user) and import-ip                     */
    /* ------------------------------------------------------------------ */

    @Test
    void importRandomTripsOnTheEleventhCallFromOneUserAndRecoversAfterAnHour() throws Exception {
        String sub = "kc-importer-" + nextIp();
        for (int i = 1; i <= 10; i++) {
            // A different address per call: import is keyed by sub, not address.
            assertThat(importRandom(player(sub), nextIp()).getResponse().getStatus())
                    .as("import #%d", i).isEqualTo(200);
        }
        MvcResult limited = importRandom(player(sub), nextIp());
        assertRateLimited(limited, "import", 10);
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(1L, Duration.ofHours(1).toSeconds());

        // Another user is unaffected.
        assertThat(importRandom(player("kc-other-" + nextIp()), nextIp()).getResponse().getStatus()).isEqualTo(200);

        CLOCK.advance(Duration.ofHours(1));
        assertThat(importRandom(player(sub), nextIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void importIpTripsAcrossUsersOnOneAddress() throws Exception {
        String ip = nextIp();
        String alice = "kc-alice-" + ip;
        String bob = "kc-bob-" + ip;
        for (int i = 1; i <= 10; i++) {
            assertThat(importRandom(player(alice), ip).getResponse().getStatus()).as("alice #%d", i).isEqualTo(200);
            assertThat(importRandom(player(bob), ip).getResponse().getStatus()).as("bob #%d", i).isEqualTo(200);
        }
        // Neither user has spent their own import bucket, but the address has spent 20.
        assertRateLimited(importRandom(player("kc-carol-" + ip), ip), "import-ip", 20);
        // A guest on the same address is refused too.
        assertRateLimited(importRandom(ANON, ip), "import-ip", 20);
        // The same users from another address are fine.
        assertThat(importRandom(player("kc-carol-" + ip), nextIp()).getResponse().getStatus()).isEqualTo(200);

        CLOCK.advance(Duration.ofHours(1));
        assertThat(importRandom(ANON, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anonymousImportIsKeyedByAddress() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 10; i++) {
            assertThat(importRandom(ANON, ip).getResponse().getStatus()).as("guest import #%d", i).isEqualTo(200);
        }
        assertRateLimited(importRandom(ANON, ip), "import", 10);
    }

    /* ------------------------------------------------------------------ */
    /* graphql-http                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void graphQlHttpGivesARealFourTwentyNineOnTheThreeHundredFirstPost() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 300; i++) {
            MvcResult ok = graphQl(ANON, ip);
            // The 121st call proves the 120/min default policy is not also charged on /graphql.
            assertThat(ok.getResponse().getStatus()).as("graphql #%d", i).isEqualTo(200);
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("graphql-http");
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING))
                    .isEqualTo(Integer.toString(300 - i));
        }
        MvcResult limited = graphQl(ANON, ip);
        assertRateLimited(limited, "graphql-http", 300);

        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(graphQl(ANON, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theGameServiceTokenGetsTwentyTimesTheGraphQlHttpBudget() throws Exception {
        MvcResult ok = graphQl(serviceToken(), nextIp());
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("graphql-http");
        assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_LIMIT)).isEqualTo("6000");
    }

    /* ------------------------------------------------------------------ */
    /* CORS                                                               */
    /* ------------------------------------------------------------------ */

    @Test
    void aCrossOriginRejectionExposesTheLimitHeaders() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 30; i++) {
            mvc.perform(get(CATEGORY_COUNTS).with(from(ip)).header(HttpHeaders.ORIGIN, ORIGIN));
        }
        MvcResult limited = mvc.perform(get(CATEGORY_COUNTS).with(from(ip)).header(HttpHeaders.ORIGIN, ORIGIN))
                .andReturn();
        assertRateLimited(limited, "bank-read", 30);
        assertThat(limited.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
        assertThat(limited.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .contains(HttpHeaders.RETRY_AFTER, LimitErrorResponses.X_RATE_LIMIT_LIMIT,
                        LimitErrorResponses.X_RATE_LIMIT_REMAINING, LimitErrorResponses.X_RATE_LIMIT_POLICY);
    }

    @Test
    void aPreflightIsNeverCharged() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 40; i++) {
            MvcResult preflight = mvc.perform(options(CATEGORY_COUNTS).with(from(ip))
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andReturn();
            assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult ok = mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING)).isEqualTo("29");
    }

    @Test
    void healthIsExempt() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 130; i++) {
            MvcResult r = mvc.perform(get("/actuator/health").with(from(ip))).andReturn();
            assertThat(r.getResponse().getStatus()).isNotEqualTo(429);
            assertThat(r.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isNull();
        }
    }
}
