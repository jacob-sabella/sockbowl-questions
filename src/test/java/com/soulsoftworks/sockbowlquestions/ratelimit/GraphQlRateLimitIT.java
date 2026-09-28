package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * WP-Q2 acceptance (the GraphQL half of M4-RL-04), auth on: through the real
 * security chain, a real Redis, the shipped policies and the real schema, every
 * top-level GraphQL field is charged its policy ({@code graphql-write} per
 * mutation field, {@code graphql-read} per query field, {@code service} for the
 * game token), aliased fields are charged one by one, a rejection is a
 * {@code RATE_LIMITED} field error with {@code retryAfterSeconds} (HTTP 200),
 * and every limit recovers when the clock advances by its refill period.
 *
 * <p>An author's budgets are the shipped capacities times the author tier
 * multiplier (2.0): 120 renames and 480 query fields a minute. The literal
 * "61st renamePacket" (tier multiplier 1) is proven by
 * {@link GraphQlRateLimitAuthOffIT}.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(QuestionsRequestGuardITSupport.ClockConfig.class)
class GraphQlRateLimitIT extends GraphQlRateLimitITSupport {

    @MockitoBean
    JwtDecoder jwtDecoder;

    static RequestPostProcessor token(String sub, String azp, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", azp)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    static RequestPostProcessor author(String sub) {
        return token(sub, "sockbowl-ng", "author", "player", "packet:read", "packet:create", "packet:update");
    }

    static RequestPostProcessor serviceToken() {
        return token("service-account-sockbowl-game-backend", "sockbowl-game-backend", "packet:read");
    }

    static String newSub() {
        return "q2-user-" + UUID.randomUUID();
    }

    boolean bucketExists(String policy, String subjectPart) {
        return rateLimitRedis.sync().exists(
                UsageKeys.rateLimit(rateLimitProperties.getKeyPrefix(), policy, subjectPart)) > 0;
    }

    /** {@code count} aliased {@code getAllDifficulties} fields: one read token each, complexity 2 each. */
    static String aliasedReads(int count) {
        return "query {" + IntStream.range(0, count).mapToObj(i -> " d" + i + ": getAllDifficulties { id }")
                .reduce("", String::concat) + " }";
    }

    /* ------------------------------------------------------------------ */
    /* graphql-write                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void renamePacketTripsGraphqlWriteAtTheAuthorCapacityAndRecoversAfterAMinute() throws Exception {
        String sub = newSub();
        String ip = nextIp();
        String packetId = seedPacket(sub);
        long capacity = capacityOf("graphql-write", Tier.AUTHOR);
        assertThat(capacity).isEqualTo(120);

        for (int i = 1; i <= capacity; i++) {
            JsonObject ok = graphQlOk(author(sub), ip, renameMutation(packetId, NAME_PREFIX + i));
            assertNoErrors(ok);
        }
        assertThat(packetName(packetId)).isEqualTo(NAME_PREFIX + capacity);

        JsonObject limited = graphQlOk(author(sub), ip, renameMutation(packetId, NAME_PREFIX + "over"));
        JsonObject error = assertFieldRateLimited(limited, "graphql-write");
        assertThat(pathOf(error)).isEqualTo("renamePacket");
        assertThat(error.getAsJsonObject("extensions").get("retryAfterSeconds").getAsLong())
                .isBetween(1L, Duration.ofMinutes(1).toSeconds());
        assertThat(packetName(packetId)).as("the rejected field never ran").isEqualTo(NAME_PREFIX + capacity);

        // Another author is unaffected.
        String other = newSub();
        assertNoErrors(graphQlOk(author(other), nextIp(), renameMutation(seedPacket(other), NAME_PREFIX + "x")));

        CLOCK.advance(Duration.ofMinutes(1));
        assertNoErrors(graphQlOk(author(sub), ip, renameMutation(packetId, NAME_PREFIX + "recovered")));
        assertThat(packetName(packetId)).isEqualTo(NAME_PREFIX + "recovered");
    }

    @Test
    void aliasedMutationFieldsAreChargedOneByOne() throws Exception {
        String sub = newSub();
        String ip = nextIp();
        String packetId = seedPacket(sub);
        long capacity = capacityOf("graphql-write", Tier.AUTHOR);

        // 110 tokens in three requests: every aliased field is charged, not every request.
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, 40)));
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, 40)));
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, 30)));

        // 20 aliased fields with 10 tokens left: r0..r9 run, r10 is rejected.
        JsonObject response = graphQlOk(author(sub), ip, aliasedRenames(packetId, 20));
        JsonObject error = assertFieldRateLimited(response, "graphql-write");
        assertThat(pathOf(error)).isEqualTo("r10");
        assertThat(packetName(packetId)).as("exactly the first %d fields ran", capacity - 110)
                .isEqualTo(NAME_PREFIX + "r9");
        // Mutation fields are non-null and run serially: the rejected field nulls
        // data and graphql-java stops, so r11..r19 are neither run nor reported.
        assertThat(response.get("data").isJsonNull()).isTrue();

        CLOCK.advance(Duration.ofMinutes(1));
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, 20)));
        assertThat(packetName(packetId)).isEqualTo(NAME_PREFIX + "r19");
    }

    @Test
    void aSeventyFieldAliasedBatchIsRejectedByTheComplexityCapWithoutBeingCharged() throws Exception {
        String sub = newSub();
        String ip = nextIp();
        String packetId = seedPacket(sub);
        String before = packetName(packetId);

        JsonObject response = graphQlOk(author(sub), ip, aliasedRenames(packetId, 70));

        assertThat(errors(response)).hasSize(1);
        assertThat(classification(errors(response).getFirst())).isEqualTo("ExecutionAborted");
        assertThat(errors(response).getFirst().get("message").getAsString())
                .startsWith("maximum query complexity exceeded 140 > 82");
        assertThat(packetName(packetId)).as("nothing ran").isEqualTo(before);
        assertThat(bucketExists("graphql-write", UsageKeys.userPart(sub))).as("nothing was charged").isFalse();
    }

    /* ------------------------------------------------------------------ */
    /* graphql-read                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void queriesTripGraphqlReadIndependentlyOfMutationsAndRecover() throws Exception {
        String sub = newSub();
        String ip = nextIp();
        String packetId = seedPacket(sub);
        long writes = capacityOf("graphql-write", Tier.AUTHOR);
        long reads = capacityOf("graphql-read", Tier.AUTHOR);
        assertThat(reads).isEqualTo(480);

        // Spend the whole write budget: queries are still allowed.
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, 40)));
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, 40)));
        assertNoErrors(graphQlOk(author(sub), ip, aliasedRenames(packetId, (int) writes - 80)));
        assertFieldRateLimited(graphQlOk(author(sub), ip, renameMutation(packetId, "x")), "graphql-write");
        assertNoErrors(graphQlOk(author(sub), ip, aliasedReads(1)));

        // Spend all but 10 read tokens (1 already used), 40 fields per request.
        long left = reads - 1 - 10;
        while (left > 0) {
            int batch = (int) Math.min(40, left);
            assertNoErrors(graphQlOk(author(sub), ip, aliasedReads(batch)));
            left -= batch;
        }

        // 20 aliased (nullable) getPacketById fields with 10 tokens left: query
        // fields run in parallel, so exactly 10 resolve and 10 are RATE_LIMITED.
        String twenty = "query {" + IntStream.range(0, 20)
                .mapToObj(i -> " g" + i + ": getPacketById(id: \"" + packetId + "\") { id }")
                .reduce("", String::concat) + " }";
        JsonObject response = graphQlOk(author(sub), ip, twenty);
        List<JsonObject> limited = rateLimited(response);
        assertThat(limited).hasSize(10);
        assertThat(limited).allSatisfy(e -> {
            assertThat(e.getAsJsonObject("extensions").get("policy").getAsString()).isEqualTo("graphql-read");
            assertThat(e.getAsJsonObject("extensions").get("retryAfterSeconds").getAsLong()).isPositive();
        });
        assertThat(limited.stream().map(GraphQlRateLimitITSupport::pathOf))
                .containsExactlyInAnyOrderElementsOf(IntStream.range(10, 20).mapToObj(i -> "g" + i).toList());
        assertThat(errors(response)).hasSize(10);
        JsonObject data = response.getAsJsonObject("data");
        IntStream.range(0, 10).forEach(i -> assertThat(data.getAsJsonObject("g" + i).get("id").getAsString())
                .isEqualTo(packetId));

        // Reads exhausted; a different caller's reads are unaffected.
        assertFieldRateLimited(graphQlOk(author(sub), ip, aliasedReads(1)), "graphql-read");
        assertNoErrors(graphQlOk(author(newSub()), nextIp(), aliasedReads(1)));

        CLOCK.advance(Duration.ofMinutes(1));
        assertNoErrors(graphQlOk(author(sub), ip, aliasedReads(1)));
        assertNoErrors(graphQlOk(author(sub), ip, renameMutation(packetId, NAME_PREFIX + "after")));
    }

    @Test
    void theGameServiceTokenIsChargedTheServicePolicy() throws Exception {
        String ip = nextIp();
        String serviceSub = "service-account-sockbowl-game-backend";

        assertNoErrors(graphQlOk(serviceToken(), ip, aliasedReads(3)));

        assertThat(bucketExists("service", UsageKeys.userPart(serviceSub))).isTrue();
        assertThat(bucketExists("graphql-read", UsageKeys.userPart(serviceSub))).isFalse();
    }

    /* ------------------------------------------------------------------ */
    /* depth and complexity over HTTP                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void aTooDeepDocumentIsAbortedBeforeAnyFieldIsCharged() throws Exception {
        String sub = newSub();
        String query = "{ __type(name: \"Packet\") { fields { type { " + "ofType { ".repeat(12) + "name"
                + " }".repeat(12) + " } } } }";

        JsonObject response = graphQlOk(author(sub), nextIp(), query);

        assertThat(errors(response)).hasSize(1);
        assertThat(classification(errors(response).getFirst())).isEqualTo("ExecutionAborted");
        assertThat(errors(response).getFirst().get("message").getAsString())
                .startsWith("maximum query depth exceeded 16 > 15");
        assertThat(bucketExists("graphql-read", UsageKeys.userPart(sub))).isFalse();
    }
}
