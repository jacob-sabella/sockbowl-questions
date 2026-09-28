package com.soulsoftworks.sockbowlquestions.quota;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared set-up for the WP-Q4 content-quota ITs: the full auth-on application
 * on the shared Neo4j Testcontainer and a private Redis Testcontainer, the
 * <b>shipped</b> {@code sockbowl.quota.*} tiers with quotas switched on, and a
 * {@link MutableClock} so daily recovery is proven by moving to the next UTC day.
 * Rate limiting stays off (the test default), so only quotas are exercised.
 *
 * <p>Every packet a test creates is named with {@link #PREFIX}, and a few
 * {@code :BankTossup} nodes in {@link #CATEGORY} are seeded for
 * {@code import-random}; both are removed after each test.
 */
abstract class ContentQuotaITSupport extends Neo4jContainerTestBase {

    static final String PREFIX = "q4quota-";
    static final String CATEGORY = "Q4QuotaCategory";
    static final String IMPORT_RANDOM = "/api/qbreader/import-random";
    static final String GRAPHQL = "/graphql";

    /** 01:00 UTC, so an hour or two of advances never crosses midnight by accident. */
    static final Instant BASE = Instant.parse("2031-05-20T01:00:00Z");
    static final MutableClock CLOCK = new MutableClock(BASE);

    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static {
        REDIS.start();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void quotaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("sockbowl.quota.enabled", () -> "true");
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
    RateLimitRedis redis;

    @Autowired
    Neo4jClient neo4j;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetState() {
        CLOCK.set(BASE);
        redis.sync().flushdb();
        cleanNeo4j();
        neo4j.query("""
                UNWIND range(1, 6) AS i
                CREATE (:BankTossup {remoteId: 'q4quota-t' + i, question: 'Q4 tossup ' + i + '?',
                                     answer: 'Answer ' + i, category: $cat, subcategory: $cat})
                """).bind(CATEGORY).to("cat").run();
    }

    @AfterEach
    void cleanNeo4j() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH $prefix OR p.ownerId STARTS WITH $prefix
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t:Tossup)
                OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b:Bonus)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp:BonusPart)
                DETACH DELETE p, t, b, bp
                """).bind(PREFIX).to("prefix").run();
        neo4j.query("MATCH (t:BankTossup) WHERE t.remoteId STARTS WITH $prefix DETACH DELETE t")
                .bind(PREFIX).to("prefix").run();
    }

    /* ------------------------------------------------------------------ */
    /* Callers                                                            */
    /* ------------------------------------------------------------------ */

    static String nextSub(String label) {
        return PREFIX + label + "-" + SEQ.incrementAndGet();
    }

    static RequestPostProcessor user(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-ng")
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    static RequestPostProcessor author(String sub) {
        return user(sub, "author", "packet:read", "packet:create", "packet:update", "packet:delete",
                "question:generate");
    }

    static RequestPostProcessor admin(String sub) {
        return user(sub, "admin", "admin:access", "packet:read", "packet:create", "packet:update",
                "packet:delete", "packet:manage-any");
    }

    static RequestPostProcessor player(String sub) {
        return user(sub, "player", "packet:read");
    }

    static final RequestPostProcessor GUEST = r -> r;

    /* ------------------------------------------------------------------ */
    /* Requests                                                           */
    /* ------------------------------------------------------------------ */

    MvcResult importRandom(RequestPostProcessor caller) throws Exception {
        return importRandom(caller, List.of(CATEGORY));
    }

    MvcResult importRandom(RequestPostProcessor caller, List<String> categories) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("tossupCount", 1);
        body.addProperty("bonusCount", 0);
        body.addProperty("name", PREFIX + "import");
        JsonArray cats = new JsonArray();
        categories.forEach(cats::add);
        body.add("categories", cats);
        return mvc.perform(post(IMPORT_RANDOM).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    JsonObject graphQl(RequestPostProcessor caller, String query) throws Exception {
        return graphQl(caller, query, null);
    }

    /** As above, with GraphQL {@code variables} (INT1: for {@code importPacket}'s free-text body). */
    JsonObject graphQl(RequestPostProcessor caller, String query, Map<String, Object> variables) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        if (variables != null) {
            body.add("variables", gson.toJsonTree(variables));
        }
        MvcResult result = mvc.perform(post(GRAPHQL).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    JsonObject createPacket(RequestPostProcessor caller, String name) throws Exception {
        return graphQl(caller, "mutation { createPacket(input: {name: \"" + name + "\"}) { id } }");
    }

    /** The created packet's id; fails the test when the mutation returned errors. */
    String createPacketOk(RequestPostProcessor caller, String name) throws Exception {
        JsonObject response = createPacket(caller, name);
        assertThat(response.has("errors")).as("errors: %s", response).isFalse();
        return response.getAsJsonObject("data").getAsJsonObject("createPacket").get("id").getAsString();
    }

    /** Minimal, always-valid ACF/NAQT plaintext (D5): one tossup, no bonuses. */
    static final String ONE_TOSSUP_TEXT = "TOSSUPS\n\n1. A perfectly fine question with an answer.\nANSWER: fine\n";

    /** {@code importPacket(dryRun: false)}: commits a new owned DRAFT (INT1). */
    JsonObject importPacketCommit(RequestPostProcessor caller, String name) throws Exception {
        String query = "mutation($text: String!, $name: String!) { "
                + "importPacket(input: {text: $text, name: $name, dryRun: false}) "
                + "{ committed packet { id } } }";
        return graphQl(caller, query, Map.of("text", ONE_TOSSUP_TEXT, "name", name));
    }

    /** {@code importPacket(dryRun: true)} (the default): a preview that must never be charged (INT1). */
    JsonObject importPacketDryRun(RequestPostProcessor caller) throws Exception {
        String query = "mutation($text: String!) { importPacket(input: {text: $text, dryRun: true}) "
                + "{ committed packet { id } } }";
        return graphQl(caller, query, Map.of("text", ONE_TOSSUP_TEXT));
    }

    /** {@code clonePacket}: an independent owned DRAFT copy (INT1); charges packets-owned, never imports. */
    JsonObject clonePacket(RequestPostProcessor caller, String id, String name) throws Exception {
        String query = "mutation($id: ID!, $name: String) { clonePacket(id: $id, name: $name) { id } }";
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", id);
        variables.put("name", name);
        return graphQl(caller, query, variables);
    }

    /** The committed packet's id from {@link #importPacketCommit}; fails the test on errors or a refusal. */
    String importPacketCommitOk(RequestPostProcessor caller, String name) throws Exception {
        JsonObject response = importPacketCommit(caller, name);
        assertThat(response.has("errors")).as("errors: %s", response).isFalse();
        JsonObject imported = response.getAsJsonObject("data").getAsJsonObject("importPacket");
        assertThat(imported.get("committed").getAsBoolean()).as("committed: %s", response).isTrue();
        return imported.getAsJsonObject("packet").get("id").getAsString();
    }

    /** The clone's id from {@link #clonePacket}; fails the test when the mutation returned errors. */
    String clonePacketOk(RequestPostProcessor caller, String id, String name) throws Exception {
        JsonObject response = clonePacket(caller, id, name);
        assertThat(response.has("errors")).as("errors: %s", response).isFalse();
        return response.getAsJsonObject("data").getAsJsonObject("clonePacket").get("id").getAsString();
    }

    JsonObject json(MvcResult result) throws Exception {
        return gson.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
    }

    /* ------------------------------------------------------------------ */
    /* Quota state                                                        */
    /* ------------------------------------------------------------------ */

    /** What game's QuotaOverride mirror writes to quota:override:{sub}. */
    void setOverride(String sub, String metric, long limit) {
        redis.sync().hset(UsageKeys.quotaOverride(sub), metric, Long.toString(limit));
    }

    static LocalDate today() {
        return LocalDate.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
    }

    static Instant nextUtcMidnight() {
        return today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    String dailyCounter(String sub, String metric) {
        return redis.sync().get(UsageKeys.daily(sub, metric, today()));
    }

    long ownedPackets(String sub) {
        return neo4j.query("MATCH (p:Packet {ownerId: $sub}) RETURN count(p) AS n")
                .bind(sub).to("sub").fetchAs(Long.class).one().orElse(0L);
    }

    static void assertOk(MvcResult result, String what) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("%s: %s", what, result.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** A REST 429 quota_exceeded body; {@code resetsAt} null for owned metrics. */
    void assertQuotaExceeded(MvcResult result, String metric, long limit, long used, Instant resetsAt)
            throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(429);
        JsonObject body = json(result);
        assertThat(body.get("error").getAsString()).isEqualTo("quota_exceeded");
        assertThat(body.get("metric").getAsString()).isEqualTo(metric);
        assertThat(body.get("limit").getAsLong()).isEqualTo(limit);
        assertThat(body.get("used").getAsLong()).isEqualTo(used);
        if (resetsAt == null) {
            assertThat(body.get("resetsAt").isJsonNull()).isTrue();
            assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        } else {
            assertThat(body.get("resetsAt").getAsString()).isEqualTo(resetsAt.toString());
            assertThat(Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
        }
    }

    /** A GraphQL QUOTA_EXCEEDED field error for an owned metric ({@code resetsAt} always null). */
    static void assertGraphQlQuotaExceeded(JsonObject response, String metric, long limit, long used) {
        assertGraphQlQuotaExceeded(response, metric, limit, used, null);
    }

    /** As above, for a daily metric (INT1: {@code imports}, via {@code importPacket}), whose {@code resetsAt} is set. */
    static void assertGraphQlQuotaExceeded(JsonObject response, String metric, long limit, long used,
                                           Instant resetsAt) {
        assertThat(response.getAsJsonArray("errors")).as("response: %s", response).hasSize(1);
        JsonObject extensions = response.getAsJsonArray("errors").get(0).getAsJsonObject()
                .getAsJsonObject("extensions");
        assertThat(extensions.get("classification").getAsString()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(extensions.get("error").getAsString()).isEqualTo("quota_exceeded");
        assertThat(extensions.get("metric").getAsString()).isEqualTo(metric);
        assertThat(extensions.get("limit").getAsLong()).isEqualTo(limit);
        assertThat(extensions.get("used").getAsLong()).isEqualTo(used);
        if (resetsAt == null) {
            assertThat(extensions.get("resetsAt").isJsonNull()).isTrue();
        } else {
            assertThat(extensions.get("resetsAt").getAsString()).isEqualTo(resetsAt.toString());
        }
    }
}
