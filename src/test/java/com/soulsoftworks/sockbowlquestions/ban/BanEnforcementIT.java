package com.soulsoftworks.sockbowlquestions.ban;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WP-Q4 acceptance, SEC-critical (D8, M4-AB-01 and the questions half of
 * M4-AB-02): bans that sockbowl-game publishes to the shared Redis are enforced
 * by sockbowl-questions, through the real auth-on security chain and a real
 * Redis, using exactly the wire formats game's {@code BanRedisMirror}
 * ({@code ban:{sub}} JSON) and {@code IpBanService} ({@code ipban:all} hash)
 * write.
 *
 * <p>Before WP-Q4 questions only had the no-op checkers, so every "is rejected"
 * assertion here failed (a banned subject or address got 200).
 *
 * <p>Recovery is proven by advancing a {@link MutableClock} past the 30s ban
 * cache, the 15s IP-ban refresh or the ban's own expiry, never by sleeping.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(BanEnforcementIT.ClockConfig.class)
class BanEnforcementIT extends Neo4jContainerTestBase {

    static final String COUNT = "/api/qbreader/count";
    static final String GRAPHQL = "/graphql";

    static final MutableClock CLOCK = MutableClock.startingNow();
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static {
        REDIS.start();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
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
    RateLimitRedis redis;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        redis.sync().flushdb();
        // Past every cached ban answer and IP-ban snapshot from earlier tests.
        CLOCK.advance(Duration.ofMinutes(5));
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    static String nextSub() {
        return "q4ban-" + SEQ.incrementAndGet();
    }

    static String nextIp() {
        int n = SEQ.incrementAndGet();
        return "10.44." + (n / 250) + "." + (n % 250 + 1);
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    static RequestPostProcessor user(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-ng")
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    static RequestPostProcessor player(String sub) {
        return user(sub, "player", "packet:read");
    }

    /** Exactly what game's BanRedisMirror writes. */
    void publishBan(String sub, String reason, Instant expiresAt) {
        JsonObject value = new JsonObject();
        value.addProperty("reason", reason);
        if (expiresAt == null) {
            value.add("expiresAt", JsonNull.INSTANCE);
        } else {
            value.addProperty("expiresAt", expiresAt.toString());
        }
        redis.sync().set(UsageKeys.ban(sub), value.toString());
    }

    /** Exactly what game's IpBanService mirrors into ipban:all. */
    void publishIpBan(String id, String cidr, Instant expiresAt) {
        JsonObject value = new JsonObject();
        value.addProperty("cidr", cidr);
        value.addProperty("expiresAtEpochMs", expiresAt.toEpochMilli());
        redis.sync().hset(UsageKeys.ipBans(), id, value.toString());
    }

    MvcResult rest(RequestPostProcessor caller, String ip) throws Exception {
        return mvc.perform(post(COUNT).with(caller).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    }

    MvcResult graphQl(RequestPostProcessor caller, String ip) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("query", "{ getAllDifficulties { id } }");
        return mvc.perform(post(GRAPHQL).with(caller).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    JsonObject json(MvcResult result) throws Exception {
        return gson.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
    }

    void assertBannedRest(MvcResult result, String reason, Instant expiresAt) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonObject body = json(result);
        assertThat(body.get("error").getAsString()).isEqualTo("banned");
        assertThat(body.get("reason").getAsString()).isEqualTo(reason);
        if (expiresAt == null) {
            assertThat(body.get("expiresAt").isJsonNull()).isTrue();
        } else {
            assertThat(body.get("expiresAt").getAsString()).isEqualTo(expiresAt.toString());
        }
    }

    void assertBannedGraphQl(MvcResult result, String reason) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonObject body = json(result);
        assertThat(body.has("data") && !body.get("data").isJsonNull()).as("no data for a banned caller").isFalse();
        assertThat(body.getAsJsonArray("errors")).hasSize(1);
        JsonObject extensions = body.getAsJsonArray("errors").get(0).getAsJsonObject()
                .getAsJsonObject("extensions");
        assertThat(extensions.get("classification").getAsString()).isEqualTo("BANNED");
        assertThat(extensions.get("error").getAsString()).isEqualTo("banned");
        assertThat(extensions.get("reason").getAsString()).isEqualTo(reason);
    }

    static void assertOk(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    void assertGraphQlOk(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonObject body = json(result);
        assertThat(body.has("errors")).as("errors: %s", body).isFalse();
        assertThat(body.getAsJsonObject("data").has("getAllDifficulties")).isTrue();
    }

    /* ------------------------------------------------------------------ */
    /* Subject bans                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void aPublishedSubjectBanIsA403OnRestAndRecoversAfterUnbanPlusTheCacheWindow() throws Exception {
        String sub = nextSub();
        String ip = nextIp();
        publishBan(sub, "spamming packets", null);

        assertBannedRest(rest(player(sub), ip), "spamming packets", null);

        // Unbanned in game: the key goes away. The 30s cache may still say "banned"...
        redis.sync().del(UsageKeys.ban(sub));
        assertBannedRest(rest(player(sub), ip), "spamming packets", null);
        // ...and once the window has passed the subject is let through again.
        CLOCK.advance(Duration.ofSeconds(31));
        assertOk(rest(player(sub), ip));
    }

    @Test
    void aSubjectBanIsAGraphQlBannedErrorWithoutExecutingAndRecovers() throws Exception {
        String sub = nextSub();
        String ip = nextIp();
        Instant expiresAt = CLOCK.instant().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
        publishBan(sub, "abusive chat", expiresAt);

        MvcResult banned = graphQl(player(sub), ip);
        assertBannedGraphQl(banned, "abusive chat");
        assertThat(json(banned).getAsJsonArray("errors").get(0).getAsJsonObject()
                .getAsJsonObject("extensions").get("expiresAt").getAsString()).isEqualTo(expiresAt.toString());

        redis.sync().del(UsageKeys.ban(sub));
        CLOCK.advance(Duration.ofSeconds(31));
        assertGraphQlOk(graphQl(player(sub), ip));
    }

    @Test
    void aBanStartsBitingWithinTheCacheWindowAfterItIsPublished() throws Exception {
        String sub = nextSub();
        String ip = nextIp();
        assertOk(rest(player(sub), ip));   // "not banned" is now cached

        publishBan(sub, "late ban", null);
        CLOCK.advance(Duration.ofSeconds(31));
        assertBannedRest(rest(player(sub), ip), "late ban", null);
        assertBannedGraphQl(graphQl(player(sub), ip), "late ban");
    }

    @Test
    void aTimedBanStopsApplyingWhenItExpiresEvenIfTheKeyIsStillThere() throws Exception {
        String sub = nextSub();
        String ip = nextIp();
        Instant expiresAt = CLOCK.instant().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
        publishBan(sub, "cool-off", expiresAt);
        redis.sync().persist(UsageKeys.ban(sub));   // even without the Redis TTL

        assertBannedRest(rest(player(sub), ip), "cool-off", expiresAt);
        CLOCK.set(expiresAt.plusSeconds(1));
        assertOk(rest(player(sub), ip));
    }

    @Test
    void aBanOnlyAffectsItsOwnSubjectAndAnonymousCallersAreUnaffected() throws Exception {
        String banned = nextSub();
        String other = nextSub();
        String ip = nextIp();
        publishBan(banned, "x", null);

        assertBannedRest(rest(player(banned), ip), "x", null);
        assertOk(rest(player(other), ip));
        assertOk(rest(r -> r, ip));
        assertGraphQlOk(graphQl(player(other), ip));
    }

    @Test
    void aCorruptBanValueStillCountsAsABan() throws Exception {
        String sub = nextSub();
        redis.sync().set(UsageKeys.ban(sub), "not-json{");

        MvcResult result = rest(player(sub), nextIp());
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(result).get("error").getAsString()).isEqualTo("banned");
    }

    @Test
    void banRejectionsAreRecordedToTheEventStream() throws Exception {
        String sub = nextSub();
        publishBan(sub, "x", null);
        rest(player(sub), nextIp());
        graphQl(player(sub), nextIp());

        // Recorded asynchronously (fire-and-forget).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<StreamMessage<String, String>> events =
                    redis.sync().xrange(UsageKeys.events(), Range.create("-", "+"));
            assertThat(events).anySatisfy(e -> assertThat(e.getBody()).containsEntry("kind", "ban")
                    .containsEntry("sub", sub).containsEntry("svc", "questions"));
        });
    }

    /* ------------------------------------------------------------------ */
    /* IP bans                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void anIpBanInTheMirrorIsA403ForAnyoneFromThatRangeOnRestAndGraphQlAndLiftsAtExpiry() throws Exception {
        String ip = "203.0.113.77";
        Instant expiresAt = Instant.ofEpochMilli(CLOCK.instant().plus(Duration.ofHours(1)).toEpochMilli());
        publishIpBan("11111111-2222-3333-4444-555555555555", "203.0.113.0/24", expiresAt);
        CLOCK.advance(Duration.ofSeconds(16));   // past the 15s refresh of the in-memory copy

        for (MvcResult result : List.of(rest(r -> r, ip), rest(player(nextSub()), ip),
                graphQl(r -> r, ip), graphQl(player(nextSub()), ip))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            JsonObject body = json(result);
            assertThat(body.get("error").getAsString()).isEqualTo("ip_banned");
            assertThat(Instant.parse(body.get("expiresAt").getAsString())).isEqualTo(expiresAt);
        }
        // Outside the range: unaffected.
        assertOk(rest(r -> r, "203.0.114.1"));

        // The ban expires (the mirror entry may linger until game prunes it).
        CLOCK.set(expiresAt.plusSeconds(1));
        assertOk(rest(r -> r, ip));
    }

    @Test
    void anIpUnbanInTheMirrorLiftsAfterTheRefreshInterval() throws Exception {
        String ip = "198.51.100.9";
        publishIpBan("ban-1", ip, CLOCK.instant().plus(Duration.ofDays(1)));
        CLOCK.advance(Duration.ofSeconds(16));
        assertThat(rest(r -> r, ip).getResponse().getStatus()).isEqualTo(403);

        redis.sync().hdel(UsageKeys.ipBans(), "ban-1");
        CLOCK.advance(Duration.ofSeconds(16));
        assertOk(rest(r -> r, ip));
    }
}
