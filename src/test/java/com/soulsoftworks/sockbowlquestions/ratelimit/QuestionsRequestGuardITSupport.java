package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Shared set-up for the questions request-guard ITs (plan m4-limits WP-Q1): the
 * full application on a real Neo4j ({@link Neo4jContainerTestBase}) and a real
 * Redis Testcontainer, the <b>shipped</b> {@code sockbowl.ratelimit.*} policies
 * from {@code src/main/resources/application.yml} with the limiter switched back
 * on (the test {@code config/application.yml} turns it off for every other
 * test), and a {@link MutableClock} so recovery is proven by advancing time,
 * never by sleeping. Only {@link QbreaderImportService} is mocked, so the import
 * and bank endpoints never call qbreader or need bank data.
 *
 * <p>Every test uses its own client IPs and subjects ({@link #nextIp()}), and
 * Redis is flushed before each test, so buckets never leak between tests.
 */
abstract class QuestionsRequestGuardITSupport extends Neo4jContainerTestBase {

    static final String IMPORT_RANDOM = "/api/qbreader/import-random";
    static final String COUNT = "/api/qbreader/count";
    static final String CATEGORY_COUNTS = "/api/qbreader/category-counts";
    static final String GRAPHQL = "/graphql";
    static final String ORIGIN = "http://localhost:4200";

    static final MutableClock CLOCK = MutableClock.startingNow();

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    /** One Redis per JVM, never stopped by JUnit (Ryuk removes it), so cached contexts keep pointing at it. */
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void limiterProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("sockbowl.ratelimit.enabled", () -> "true");
        registry.add("sockbowl.quota.enabled", () -> "false");
        registry.add("sockbowl.cors.allowed-origins", () -> ORIGIN);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

    final Gson gson = new Gson();

    @Autowired
    MockMvc mvc;

    @Autowired
    RateLimitRedis rateLimitRedis;

    @MockitoBean
    QbreaderImportService importService;

    @BeforeEach
    void resetLimiterAndStubImports() {
        rateLimitRedis.sync().flushdb();
        Packet packet = Packet.builder().id(UUID.randomUUID().toString()).name("rl-packet").build();
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                any())).thenReturn(new QbreaderImportService.ImportOutcome(packet, List.of(), 0, 0));
        when(importService.countAvailable(any())).thenReturn(new QbreaderImportService.AvailableCount(1, 1));
        when(importService.categoryCounts()).thenReturn(Map.of("Science", 1));
        when(importService.taxonomyCounts()).thenReturn(Map.of());
    }

    /** A fresh client address per call (10.43.x.y). */
    static String nextIp() {
        int n = IP_SEQ.incrementAndGet();
        return "10.43." + (n / 250) + "." + (n % 250 + 1);
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    static final RequestPostProcessor ANON = r -> r;

    static String importBody() {
        JsonObject o = new JsonObject();
        o.addProperty("tossupCount", 1);
        o.addProperty("bonusCount", 0);
        return o.toString();
    }

    static String graphQlBody() {
        JsonObject o = new JsonObject();
        o.addProperty("query", "{ getAllDifficulties { id } }");
        return o.toString();
    }

    static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }

    static void assertRateLimited(MvcResult result, String policy, long limit) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo(policy);
        assertThat(result.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_LIMIT))
                .isEqualTo(Long.toString(limit));
        assertThat(result.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING)).isEqualTo("0");
        long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isPositive();

        JsonObject body = new Gson().fromJson(result.getResponse().getContentAsString(), JsonObject.class);
        assertThat(body.get("error").getAsString()).isEqualTo("rate_limited");
        assertThat(body.get("policy").getAsString()).isEqualTo(policy);
        assertThat(body.get("retryAfterSeconds").getAsLong()).isEqualTo(retryAfter);
        assertThat(body.get("message").getAsString()).isEqualTo("Too many requests");
    }
}
