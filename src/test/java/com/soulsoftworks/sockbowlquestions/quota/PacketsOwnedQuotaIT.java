package com.soulsoftworks.sockbowlquestions.quota;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WP-Q4 acceptance (the {@code packets-owned} half of M4-UQ-01, D10): the number
 * of packets a user owns is capped at the tier default or the admin override
 * game mirrors to {@code quota:override:{sub}}, on every path that creates an
 * owned packet (GraphQL {@code createPacket}, an owned {@code import-random},
 * {@code POST /api/packets/generate}), and the quota recovers when the owner
 * deletes a packet.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(ContentQuotaITSupport.ClockConfig.class)
class PacketsOwnedQuotaIT extends ContentQuotaITSupport {

    @MockitoBean
    ChatClientFactory chatClientFactory;

    @Test
    void theThirdCreateIsQuotaExceededWithAnOverrideOfTwoAndDeletingOneRecovers() throws Exception {
        String sub = nextSub("owned");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 2);

        String first = createPacketOk(author(sub), PREFIX + "p1");
        createPacketOk(author(sub), PREFIX + "p2");

        JsonObject third = createPacket(author(sub), PREFIX + "p3");
        assertGraphQlQuotaExceeded(third, UsageKeys.PACKETS_OWNED, 2, 2);
        assertThat(ownedPackets(sub)).isEqualTo(2);

        JsonObject deleted = graphQl(author(sub), "mutation { deletePacket(id: \"" + first + "\") }");
        assertThat(deleted.getAsJsonObject("data").get("deletePacket").getAsBoolean()).isTrue();

        createPacketOk(author(sub), PREFIX + "p3");
        assertThat(ownedPackets(sub)).isEqualTo(2);
    }

    @Test
    void theShippedAuthorDefaultAppliesWithoutAnOverrideAndAnOverrideCanRaiseOrLiftIt() throws Exception {
        String sub = nextSub("default");
        // Shipped D10 author default is 300: a first packet is well inside it.
        createPacketOk(author(sub), PREFIX + "d1");

        setOverride(sub, UsageKeys.PACKETS_OWNED, 1);
        assertGraphQlQuotaExceeded(createPacket(author(sub), PREFIX + "d2"), UsageKeys.PACKETS_OWNED, 1, 1);

        setOverride(sub, UsageKeys.PACKETS_OWNED, -1);   // "unlimited"
        createPacketOk(author(sub), PREFIX + "d2");
    }

    @Test
    void adminsAreNeverLimited() throws Exception {
        String sub = nextSub("admin");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 0);   // ignored: ADMIN skips quotas
        createPacketOk(admin(sub), PREFIX + "a1");
    }

    @Test
    void usersAreCountedSeparately() throws Exception {
        String full = nextSub("full");
        String other = nextSub("other");
        setOverride(full, UsageKeys.PACKETS_OWNED, 0);

        assertGraphQlQuotaExceeded(createPacket(author(full), PREFIX + "x"), UsageKeys.PACKETS_OWNED, 0, 0);
        createPacketOk(author(other), PREFIX + "y");
    }

    @Test
    void anOwnedImportCountsTowardAndIsBlockedByPacketsOwnedWithoutChargingImports() throws Exception {
        String sub = nextSub("import");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 1);

        assertOk(importRandom(author(sub)), "first owned import");
        assertThat(ownedPackets(sub)).isEqualTo(1);
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("1");

        assertQuotaExceeded(importRandom(author(sub)), UsageKeys.PACKETS_OWNED, 1, 1, null);
        // Rejected before the daily imports counter was charged.
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("1");
        assertThat(ownedPackets(sub)).isEqualTo(1);
    }

    /* --------------------------- INT1: GraphQL importPacket/clonePacket --------------------------- */

    @Test
    void importPacketDryRunFalseCountsTowardAndIsBlockedByPacketsOwnedWithoutChargingImports() throws Exception {
        String sub = nextSub("gqlImportOwned");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 1);

        importPacketCommitOk(author(sub), PREFIX + "gql-first");
        assertThat(ownedPackets(sub)).isEqualTo(1);
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("1");

        assertGraphQlQuotaExceeded(importPacketCommit(author(sub), PREFIX + "gql-second"),
                UsageKeys.PACKETS_OWNED, 1, 1);
        // Rejected before the daily imports counter was charged (packets-owned is checked first).
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("1");
        assertThat(ownedPackets(sub)).isEqualTo(1);
    }

    @Test
    void importPacketDryRunTrueIsNeverBlockedByPacketsOwned() throws Exception {
        String sub = nextSub("gqlDryRunOwned");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 0);

        JsonObject response = importPacketDryRun(author(sub));
        assertThat(response.has("errors")).as("errors: %s", response).isFalse();
        assertThat(ownedPackets(sub)).isZero();
    }

    @Test
    void clonePacketCountsTowardAndIsBlockedByPacketsOwned() throws Exception {
        // Clones its own packet (packet:manage-any is a separate concern, covered by
        // PacketImportCypherIT); what's under test here is only the packets-owned count.
        String sub = nextSub("gqlCloneOwned");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 2);
        String sourceId = createPacketOk(author(sub), PREFIX + "clone-src");
        assertThat(ownedPackets(sub)).isEqualTo(1);

        clonePacketOk(author(sub), sourceId, PREFIX + "clone-1");
        assertThat(ownedPackets(sub)).isEqualTo(2);

        assertGraphQlQuotaExceeded(clonePacket(author(sub), sourceId, PREFIX + "clone-2"),
                UsageKeys.PACKETS_OWNED, 2, 2);
        assertThat(ownedPackets(sub)).isEqualTo(2);
    }

    @Test
    void generatingAPacketIsBlockedByPacketsOwnedBeforeAnyAiWork() throws Exception {
        String sub = nextSub("gen");
        setOverride(sub, UsageKeys.PACKETS_OWNED, 0);

        JsonObject body = new JsonObject();
        body.addProperty("topic", "q4 quota topic");
        body.addProperty("questionCount", 1);
        body.addProperty("generateBonuses", false);
        MvcResult result = mvc.perform(post("/api/packets/generate").with(author(sub))
                .header("X-API-Key", "sk-q4-byo").header("X-Model", "byo-model")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();

        assertQuotaExceeded(result, UsageKeys.PACKETS_OWNED, 0, 0, null);
        verifyNoInteractions(chatClientFactory);
        assertThat(dailyCounter(sub, UsageKeys.AI_GENERATIONS)).isNull();
    }
}
