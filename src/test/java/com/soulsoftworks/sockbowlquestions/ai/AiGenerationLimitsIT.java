package com.soulsoftworks.sockbowlquestions.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.ratelimit.Decision;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WP-Q3 acceptance (M4-UQ-02, the ai-generate part of M4-RL-04): AI cost
 * controls through the real auth-on security chain, a real Redis, a real Neo4j
 * and the <b>shipped</b> {@code sockbowl.ratelimit.*}, {@code sockbowl.quota.*}
 * and {@code sockbowl.ai.*} settings. Every limit is shown to trip and then to
 * recover, by advancing a {@link MutableClock} (never by sleeping).
 *
 * <p>No AI provider is ever called: {@link ChatClientFactory} is replaced so that
 * both server-key and BYO-key calls get a {@link ChatClient} over the
 * {@link ScriptedChatModel} double.
 *
 * <p>This class owns a private Redis container (not the shared one the other
 * limiter ITs use), because {@link #redisDownFailsClosedAndRecovers} pauses it.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        // The server key is usable (the shipped default requires BYO keys).
        "sockbowl.ai.require-user-api-key=false",
        "sockbowl.ai.server-key.allowed-models=" + AiGenerationLimitsIT.SERVER_MODEL
})
@AutoConfigureMockMvc
@Import(AiGenerationLimitsIT.ClockConfig.class)
class AiGenerationLimitsIT extends Neo4jContainerTestBase {

    static final String SERVER_MODEL = "server-model-q3";
    static final String GENERATE = "/api/packets/generate";
    static final String TOPIC = "q3ai-science";
    static final String NAME_PREFIX = "q3ai-";

    /** 01:00 UTC, so a few one-hour advances never cross midnight by accident. */
    static final Instant BASE = Instant.parse("2031-03-10T01:00:00Z");
    static final MutableClock CLOCK = new MutableClock(BASE);

    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static {
        REDIS.start();
    }

    static final ScriptedChatModel CHAT = new ScriptedChatModel();

    private static final AtomicInteger SUB_SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void limiterProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("sockbowl.ratelimit.enabled", () -> "true");
        registry.add("sockbowl.quota.enabled", () -> "true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

    private final Gson gson = new Gson();

    @Autowired
    MockMvc mvc;

    @Autowired
    RateLimitRedis rateLimitRedis;

    @Autowired
    RateLimitService rateLimitService;

    @Autowired
    AiSecurityProperties aiProperties;

    @Autowired
    PacketRepository packetRepository;

    @Autowired
    Neo4jClient neo4j;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    ChatClientFactory chatClientFactory;

    @BeforeEach
    void reset() {
        CLOCK.set(BASE);
        CHAT.reset();
        rateLimitRedis.sync().flushdb();
        when(chatClientFactory.getChatClient(any(), nullable(String.class))).thenAnswer(inv -> ChatClient.builder(CHAT).build());
    }

    @AfterEach
    void cleanup() {
        CHAT.reset();
        neo4j.query("""
                MATCH (p:Packet) WHERE p.ownerId STARTS WITH $prefix
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t)
                OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp)
                DETACH DELETE p, t, b, bp
                """)
                .bind(NAME_PREFIX).to("prefix")
                .run();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    static String nextSub(String label) {
        return "q3ai-" + label + "-" + SUB_SEQ.incrementAndGet();
    }

    static RequestPostProcessor author(String sub) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-ng")
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("author"))))
                .authorities(List.<GrantedAuthority>of(
                        new SimpleGrantedAuthority("author"),
                        new SimpleGrantedAuthority("question:generate"),
                        new SimpleGrantedAuthority("packet:create"),
                        new SimpleGrantedAuthority("packet:read")));
    }

    static String body(String topic) {
        JsonObject o = new JsonObject();
        o.addProperty("topic", topic);
        o.addProperty("questionCount", 1);
        o.addProperty("generateBonuses", false);
        return o.toString();
    }

    MvcResult generate(String sub, String... headers) throws Exception {
        return generateWithBody(sub, body(TOPIC), headers);
    }

    MvcResult generateWithBody(String sub, String json, String... headers) throws Exception {
        MockHttpServletRequestBuilder request = post(GENERATE).with(author(sub))
                .contentType(MediaType.APPLICATION_JSON).content(json);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request = request.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    static String[] byoKey(String model) {
        return new String[]{"X-API-Key", "sk-byo-test", "X-Model", model};
    }

    static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    JsonObject json(MvcResult result) throws Exception {
        return gson.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
    }

    static LocalDate today() {
        return LocalDate.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
    }

    static Instant nextUtcMidnight() {
        return today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    String userCounter(String sub, String metric) {
        return rateLimitRedis.sync().get(UsageKeys.daily(sub, metric, today()));
    }

    String globalCounter() {
        return rateLimitRedis.sync().get(UsageKeys.globalDaily(UsageKeys.AI_SERVERKEY, today()));
    }

    void assertOk(MvcResult result, String what) throws Exception {
        assertThat(status(result)).as("%s: %s", what, result.getResponse().getContentAsString()).isEqualTo(200);
    }

    void assertRateLimited(MvcResult result, String policy) throws Exception {
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(429);
        JsonObject body = json(result);
        assertThat(body.get("error").getAsString()).isEqualTo("rate_limited");
        assertThat(body.get("policy").getAsString()).isEqualTo(policy);
        long retryAfter = body.get("retryAfterSeconds").getAsLong();
        assertThat(retryAfter).isPositive();
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(Long.toString(retryAfter));
        assertThat(result.getResponse().getHeader("X-RateLimit-Policy")).isEqualTo(policy);
    }

    void assertQuotaExceeded(MvcResult result, String metric, long limit, long used) throws Exception {
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(429);
        JsonObject body = json(result);
        assertThat(body.get("error").getAsString()).isEqualTo("quota_exceeded");
        assertThat(body.get("metric").getAsString()).isEqualTo(metric);
        assertThat(body.get("limit").getAsLong()).isEqualTo(limit);
        assertThat(body.get("used").getAsLong()).isEqualTo(used);
        assertThat(body.get("resetsAt").getAsString()).isEqualTo(nextUtcMidnight().toString());
        assertThat(Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
    }

    /* ------------------------------------------------------------------ */
    /* Per-user daily quota (ai.generations, D10)                         */
    /* ------------------------------------------------------------------ */

    @Test
    void dailyQuotaTripsOnTheTwentyFirstServerKeyCallAndRecoversAtUtcMidnight() throws Exception {
        String sub = nextSub("quota");
        for (int i = 1; i <= 20; i++) {
            assertOk(generate(sub), "server-key generate #" + i);
            if (i % 10 == 0) {
                // ai-generate allows 10/h; the quota is what this test is about.
                CLOCK.advance(Duration.ofHours(1));
            }
        }
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).isEqualTo("20");
        assertThat(userCounter(sub, UsageKeys.AI_QUESTIONS)).isEqualTo("20");

        MvcResult limited = generate(sub);
        assertQuotaExceeded(limited, UsageKeys.AI_GENERATIONS, 20, 20);
        assertThat(CHAT.calls()).isEqualTo(20);

        // A new UTC day resets the quota.
        CLOCK.set(nextUtcMidnight().plusSeconds(1));
        assertOk(generate(sub), "generate on the next UTC day");
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).isEqualTo("1");
    }

    /* ------------------------------------------------------------------ */
    /* ai-generate rate policy (BYO keys: rate limit only, D11)           */
    /* ------------------------------------------------------------------ */

    @Test
    void byoKeyCallsAreNotCountedButTripTheRateLimitOnTheEleventhCallAndRecoverAfterAnHour() throws Exception {
        String sub = nextSub("byo");
        for (int i = 1; i <= 10; i++) {
            assertOk(generate(sub, byoKey("gpt-anything")), "BYO generate #" + i);
        }
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).as("BYO calls are not quota-counted").isNull();
        assertThat(globalCounter()).as("BYO calls do not use the server budget").isNull();

        assertRateLimited(generate(sub, byoKey("gpt-anything")), AiGenerationGuard.POLICY_AI_GENERATE);
        assertThat(CHAT.calls()).isEqualTo(10);

        // Another author has an independent bucket.
        assertOk(generate(nextSub("byo-other"), byoKey("gpt-anything")), "other author");

        CLOCK.advance(Duration.ofHours(1));
        assertOk(generate(sub, byoKey("gpt-anything")), "BYO generate after an hour");
    }

    /* ------------------------------------------------------------------ */
    /* ai-concurrency lock                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void aSecondConcurrentCallFromOneUserIsRejectedUntilTheFirstFinishes() throws Exception {
        String sub = nextSub("conc");
        CHAT.blockFirstCall();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> generate(sub));
            assertThat(CHAT.awaitBlockedCall(30, TimeUnit.SECONDS)).as("first call reached the model").isTrue();
            assertThat(rateLimitRedis.sync().get(UsageKeys.aiInflight(sub))).isNotNull();

            MvcResult second = generate(sub);
            assertRateLimited(second, AiGenerationGuard.POLICY_AI_CONCURRENCY);
            assertThat(json(second).get("retryAfterSeconds").getAsLong())
                    .isEqualTo(aiProperties.getConcurrencyRetryAfter().toSeconds());

            // The lock is per user: another author is not blocked meanwhile.
            assertOk(generate(nextSub("conc-other")), "another author while the first call runs");

            CHAT.releaseBlocked();
            assertOk(first.get(30, TimeUnit.SECONDS), "the first call");
        } finally {
            CHAT.releaseBlocked();
            executor.shutdownNow();
        }
        assertThat(rateLimitRedis.sync().get(UsageKeys.aiInflight(sub))).as("lock released").isNull();
        assertOk(generate(sub), "a new call after the first finished");
    }

    /* ------------------------------------------------------------------ */
    /* Global server-key budget (ai.serverkey, D11)                       */
    /* ------------------------------------------------------------------ */

    @Test
    void theGlobalServerKeyBudgetRejectsAnotherUserAndRecoversTheNextDay() throws Exception {
        long shipped = aiProperties.getServerKey().getDailyBudget();
        assertThat(shipped).isEqualTo(200);
        aiProperties.getServerKey().setDailyBudget(2);
        try {
            String first = nextSub("budget-a");
            String second = nextSub("budget-b");
            assertOk(generate(first), "budget call 1");
            assertOk(generate(first), "budget call 2");
            assertThat(globalCounter()).isEqualTo("2");

            MvcResult rejected = generate(second);
            assertQuotaExceeded(rejected, UsageKeys.AI_SERVERKEY, 2, 2);
            assertThat(userCounter(second, UsageKeys.AI_GENERATIONS))
                    .as("the rejected user's own quota was refunded").isEqualTo("0");

            // BYO keys are not subject to the server budget.
            assertOk(generate(second, byoKey("gpt-anything")), "BYO while the budget is spent");

            CLOCK.set(nextUtcMidnight().plusSeconds(1));
            assertOk(generate(second), "server-key call the next day");
            assertThat(globalCounter()).isEqualTo("1");
        } finally {
            aiProperties.getServerKey().setDailyBudget(shipped);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Fail closed with Redis down (D12)                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void redisDownFailsClosedAndRecovers() throws Exception {
        String sub = nextSub("down");
        assertOk(generate(sub), "before the outage");

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            MvcResult serverKey = generate(sub);
            assertThat(status(serverKey)).as(serverKey.getResponse().getContentAsString()).isEqualTo(503);
            JsonObject body = json(serverKey);
            assertThat(body.get("error").getAsString()).isEqualTo("limiter_unavailable");
            assertThat(body.get("policy").getAsString()).isEqualTo(AiGenerationGuard.POLICY_AI_GENERATE);

            MvcResult byo = generate(sub, byoKey("gpt-anything"));
            assertThat(status(byo)).as("BYO also fails closed").isEqualTo(503);
            assertThat(CHAT.calls()).as("no generation ran during the outage").isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        // Redis back: generation is allowed again.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> status(generate(nextSub("down-after")))  == 200);
        assertOk(generate(sub), "the same user after the outage");
    }

    /* ------------------------------------------------------------------ */
    /* Model allowlist, input validation, method                          */
    /* ------------------------------------------------------------------ */

    @Test
    void aModelOffTheAllowlistIsRejectedWithTheServerKeyButAllowedWithBYO() throws Exception {
        String sub = nextSub("model");
        MvcResult rejected = generate(sub, "X-Model", "gpt-not-allowed");
        assertThat(status(rejected)).isEqualTo(400);
        assertThat(rejected.getResponse().getContentAsString()).contains("gpt-not-allowed");
        assertThat(CHAT.calls()).isZero();
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).as("a 400 costs nothing").isNull();
        assertThat(globalCounter()).isNull();

        assertOk(generate(sub, "X-Model", SERVER_MODEL), "server key with an allowed model");
        assertOk(generate(sub, byoKey("gpt-not-allowed")), "BYO with any model");
    }

    @Test
    void anOverlongTopicOrContextOrCountIsA400AndGetIsA405() throws Exception {
        String sub = nextSub("validation");
        assertThat(status(generateWithBody(sub, body("x".repeat(201))))).isEqualTo(400);
        assertThat(status(generateWithBody(sub, body("x".repeat(200))))).isEqualTo(200);

        JsonObject longContext = gson.fromJson(body(TOPIC), JsonObject.class);
        longContext.addProperty("additionalContext", "c".repeat(2001));
        assertThat(status(generateWithBody(sub, longContext.toString()))).isEqualTo(400);

        JsonObject tooMany = gson.fromJson(body(TOPIC), JsonObject.class);
        tooMany.addProperty("questionCount", 31);
        assertThat(status(generateWithBody(sub, tooMany.toString()))).isEqualTo(400);

        JsonObject noTopic = new JsonObject();
        assertThat(status(generateWithBody(sub, noTopic.toString()))).isEqualTo(400);

        assertThat(status(mvc.perform(get(GENERATE).param("topic", TOPIC).with(author(sub))).andReturn()))
                .isEqualTo(405);
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).as("only the one valid call counted").isEqualTo("1");
    }

    /* ------------------------------------------------------------------ */
    /* Refund on failure                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void aFailedGenerationRefundsTheQuotaAndTheBudget() throws Exception {
        String sub = nextSub("refund");
        CHAT.failAlways();
        MvcResult failed = generate(sub);
        assertThat(status(failed)).isEqualTo(500);
        assertThat(CHAT.calls()).isPositive();
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).isEqualTo("0");
        assertThat(globalCounter()).isEqualTo("0");
        assertThat(userCounter(sub, UsageKeys.AI_QUESTIONS)).isNull();
        assertThat(rateLimitRedis.sync().get(UsageKeys.aiInflight(sub))).as("lock released").isNull();

        CHAT.reset();
        assertOk(generate(sub), "a later call");
        assertThat(userCounter(sub, UsageKeys.AI_GENERATIONS)).isEqualTo("1");
        assertThat(globalCounter()).isEqualTo("1");
    }

    /* ------------------------------------------------------------------ */
    /* GraphQL generateAndAddTossup                                       */
    /* ------------------------------------------------------------------ */

    private String seedPacket(String ownerSub) {
        Packet packet = Packet.builder()
                .name(NAME_PREFIX + UUID.randomUUID())
                .ownerId(ownerSub)
                .ownerDisplayName(ownerSub)
                .visibility(PacketVisibility.DRAFT)
                .build();
        return packetRepository.save(packet).getId();
    }

    private JsonObject generateAndAddTossup(String sub, String packetId, String extraInput) throws Exception {
        String query = "mutation { generateAndAddTossup(packetId: \"" + packetId
                + "\", input: {topic: \"" + TOPIC + "\"" + extraInput + "}) { id } }";
        JsonObject request = new JsonObject();
        request.addProperty("query", query);
        MvcResult first = mvc.perform(post("/graphql").with(author(sub))
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andReturn();
        MvcResult result = first.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(first)).andReturn() : first;
        assertThat(status(result)).isEqualTo(200);
        return json(result);
    }

    private static JsonObject firstError(JsonObject response) {
        JsonArray errors = response.getAsJsonArray("errors");
        assertThat(errors).as("errors in %s", response).isNotNull();
        return errors.get(0).getAsJsonObject();
    }

    @Test
    void graphQlGenerateAndAddTossupReturnsQuotaExceeded() throws Exception {
        String sub = nextSub("gql-quota");
        String packetId = seedPacket(sub);
        // An admin override of 1 (quota:override:{sub}) instead of 20 calls.
        rateLimitRedis.sync().hset(UsageKeys.quotaOverride(sub), UsageKeys.AI_GENERATIONS, "1");

        JsonObject ok = generateAndAddTossup(sub, packetId, "");
        assertThat(ok.get("errors")).as("%s", ok).isNull();

        JsonObject error = firstError(generateAndAddTossup(sub, packetId, ""));
        JsonObject extensions = error.getAsJsonObject("extensions");
        assertThat(extensions.get("classification").getAsString()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(extensions.get("metric").getAsString()).isEqualTo(UsageKeys.AI_GENERATIONS);
        assertThat(extensions.get("limit").getAsLong()).isEqualTo(1);
        assertThat(extensions.get("used").getAsLong()).isEqualTo(1);
        assertThat(extensions.get("resetsAt").getAsString()).isEqualTo(nextUtcMidnight().toString());
        assertThat(CHAT.calls()).isEqualTo(1);
    }

    @Test
    void graphQlGenerateAndAddTossupReturnsRateLimited() throws Exception {
        String sub = nextSub("gql-rate");
        String packetId = seedPacket(sub);
        // Spend this author's ai-generate bucket (10/h) up front.
        Decision drained = rateLimitService.tryConsume(AiGenerationGuard.POLICY_AI_GENERATE,
                new LimitSubject(sub, "127.0.0.1", Tier.AUTHOR), 10);
        assertThat(drained.allowed()).isTrue();

        JsonObject error = firstError(generateAndAddTossup(sub, packetId,
                ", apiKey: \"sk-byo-test\", model: \"gpt-anything\""));
        JsonObject extensions = error.getAsJsonObject("extensions");
        assertThat(extensions.get("classification").getAsString()).isEqualTo("RATE_LIMITED");
        assertThat(extensions.get("policy").getAsString()).isEqualTo(AiGenerationGuard.POLICY_AI_GENERATE);
        assertThat(extensions.get("retryAfterSeconds").getAsLong()).isPositive();
        assertThat(CHAT.calls()).isZero();

        CLOCK.advance(Duration.ofHours(1));
        JsonObject recovered = generateAndAddTossup(sub, packetId, ", apiKey: \"sk-byo-test\", model: \"gpt-anything\"");
        assertThat(recovered.get("errors")).as("%s", recovered).isNull();
    }
}
