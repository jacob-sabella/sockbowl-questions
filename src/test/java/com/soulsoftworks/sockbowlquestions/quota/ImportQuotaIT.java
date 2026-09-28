package com.soulsoftworks.sockbowlquestions.quota;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-Q4 acceptance (the {@code imports} half of M4-UQ-01, D10): an author may
 * make 10 owned {@code import-random} packets per UTC day; the 11th is a 429
 * {@code quota_exceeded} and the quota recovers at the next UTC midnight. A
 * failed import is refunded.
 *
 * <p>Adjusted from the plan for D15 (which post-dates it): a player or guest is
 * no longer refused by the {@code packet:create} gate. They get an ownerless,
 * game-only EPHEMERAL packet, which counts toward no quota (it is bounded by the
 * {@code import}/{@code import-ip} rate policies instead). What the plan's case
 * protects still holds and is asserted: those callers never charge or read an
 * {@code imports} counter.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(ContentQuotaITSupport.ClockConfig.class)
class ImportQuotaIT extends ContentQuotaITSupport {

    @Test
    void tenImportsADayThenQuotaExceededThenRecoversTheNextUtcDay() throws Exception {
        String sub = nextSub("daily");
        for (int i = 1; i <= 10; i++) {
            assertOk(importRandom(author(sub)), "owned import #" + i);
        }
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("10");

        MvcResult eleventh = importRandom(author(sub));
        assertQuotaExceeded(eleventh, UsageKeys.IMPORTS, 10, 10, nextUtcMidnight());
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("10");
        assertThat(ownedPackets(sub)).isEqualTo(10);

        // Still the same UTC day an hour later.
        CLOCK.advance(Duration.ofHours(1));
        assertQuotaExceeded(importRandom(author(sub)), UsageKeys.IMPORTS, 10, 10, nextUtcMidnight());

        // Next UTC day: a fresh counter.
        CLOCK.set(nextUtcMidnight().plusSeconds(1));
        assertOk(importRandom(author(sub)), "first import of the next day");
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("1");
    }

    @Test
    void anAdminOverrideRaisesTheDailyLimit() throws Exception {
        String sub = nextSub("override");
        setOverride(sub, UsageKeys.IMPORTS, 2);
        assertOk(importRandom(author(sub)), "#1");
        assertOk(importRandom(author(sub)), "#2");
        assertQuotaExceeded(importRandom(author(sub)), UsageKeys.IMPORTS, 2, 2, nextUtcMidnight());

        setOverride(sub, UsageKeys.IMPORTS, 3);
        assertOk(importRandom(author(sub)), "#3 after the override was raised");
    }

    @Test
    void aFailedImportIsRefunded() throws Exception {
        String sub = nextSub("refund");
        setOverride(sub, UsageKeys.IMPORTS, 1);

        MvcResult nothingMatches = importRandom(author(sub), List.of("Q4NoSuchCategory"));
        assertThat(nothingMatches.getResponse().getStatus()).isEqualTo(404);
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("0");

        // The one allowed import is still available.
        assertOk(importRandom(author(sub)), "the refunded unit");
        assertQuotaExceeded(importRandom(author(sub)), UsageKeys.IMPORTS, 1, 1, nextUtcMidnight());
    }

    @Test
    void playersAndGuestsGetEphemeralPacketsThatChargeNoQuota() throws Exception {
        String player = nextSub("player");
        // Their D10 imports and packets-owned quotas are 0, yet (D15) they may still
        // build a game-only packet, many times.
        for (int i = 0; i < 12; i++) {
            assertOk(importRandom(player(player)), "player ephemeral import #" + i);
            assertOk(importRandom(GUEST), "guest ephemeral import #" + i);
        }
        assertThat(dailyCounter(player, UsageKeys.IMPORTS)).isNull();
        assertThat(redis.sync().keys("usage:*")).isEmpty();
        assertThat(ownedPackets(player)).isZero();
    }

    /* --------------------------- INT1: GraphQL importPacket/clonePacket --------------------------- */

    @Test
    void importPacketDryRunFalseChargesTheDailyImportsCounterTheSameAsImportRandom() throws Exception {
        String sub = nextSub("gqlImport");
        for (int i = 1; i <= 10; i++) {
            importPacketCommitOk(author(sub), PREFIX + "gql-" + i);
        }
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("10");

        JsonObject eleventh = importPacketCommit(author(sub), PREFIX + "gql-11");
        assertGraphQlQuotaExceeded(eleventh, UsageKeys.IMPORTS, 10, 10, nextUtcMidnight());
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("10");
        assertThat(ownedPackets(sub)).isEqualTo(10);

        // Next UTC day: a fresh counter, same as the REST import.
        CLOCK.set(nextUtcMidnight().plusSeconds(1));
        importPacketCommitOk(author(sub), PREFIX + "gql-next-day");
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isEqualTo("1");
    }

    @Test
    void importPacketDryRunIsNeverChargedRegardlessOfHowManyTimesItIsCalled() throws Exception {
        String sub = nextSub("gqlDryRun");
        setOverride(sub, UsageKeys.IMPORTS, 1);
        for (int i = 0; i < 15; i++) {
            JsonObject response = importPacketDryRun(author(sub));
            assertThat(response.has("errors")).as("errors: %s", response).isFalse();
            assertThat(response.getAsJsonObject("data").getAsJsonObject("importPacket")
                    .get("committed").getAsBoolean()).as("a dry run never commits").isFalse();
        }
        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isNull();
        assertThat(ownedPackets(sub)).isZero();

        // The daily limit of 1 set above is still fully available: nothing above touched it.
        importPacketCommitOk(author(sub), PREFIX + "gql-after-dry-runs");
        assertGraphQlQuotaExceeded(importPacketCommit(author(sub), PREFIX + "gql-second"),
                UsageKeys.IMPORTS, 1, 1, nextUtcMidnight());
    }

    @Test
    void clonePacketNeverChargesTheDailyImportsCounter() throws Exception {
        String sub = nextSub("gqlClone");
        setOverride(sub, UsageKeys.IMPORTS, 0);
        String sourceId = createPacketOk(author(sub), PREFIX + "clone-source");

        for (int i = 0; i < 5; i++) {
            clonePacketOk(author(sub), sourceId, PREFIX + "clone-" + i);
        }

        assertThat(dailyCounter(sub, UsageKeys.IMPORTS)).isNull();
    }
}
