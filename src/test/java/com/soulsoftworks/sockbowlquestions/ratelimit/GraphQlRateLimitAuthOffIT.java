package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-Q2 acceptance with auth off ({@code NoSecurityConfig}): every caller is a
 * guest keyed by address (tier multiplier 1), so the shipped {@code graphql-write}
 * policy of 60 a minute is literal here. The 61st {@code renamePacket} is a
 * {@code RATE_LIMITED} field error with {@code retryAfterSeconds > 0}, and it
 * recovers after a minute.
 */
@SpringBootTest(properties = "sockbowl.auth.enabled=false")
@AutoConfigureMockMvc
@Import(QuestionsRequestGuardITSupport.ClockConfig.class)
class GraphQlRateLimitAuthOffIT extends GraphQlRateLimitITSupport {

    @Test
    void theSixtyFirstRenamePacketIsRateLimitedAndRecoversAfterAMinute() throws Exception {
        String ip = nextIp();
        String packetId = seedPacket(null);
        assertThat(capacityOf("graphql-write", Tier.GUEST)).isEqualTo(60);

        for (int i = 1; i <= 60; i++) {
            assertNoErrors(graphQlOk(ANON, ip, renameMutation(packetId, NAME_PREFIX + i)));
        }
        JsonObject limited = graphQlOk(ANON, ip, renameMutation(packetId, NAME_PREFIX + "61"));
        JsonObject error = assertFieldRateLimited(limited, "graphql-write");
        assertThat(pathOf(error)).isEqualTo("renamePacket");
        assertThat(error.getAsJsonObject("extensions").get("retryAfterSeconds").getAsLong())
                .isBetween(1L, 60L);
        assertThat(packetName(packetId)).isEqualTo(NAME_PREFIX + "60");

        // Another address has its own bucket.
        assertNoErrors(graphQlOk(ANON, nextIp(), renameMutation(packetId, NAME_PREFIX + "other")));

        CLOCK.advance(Duration.ofMinutes(1));
        assertNoErrors(graphQlOk(ANON, ip, renameMutation(packetId, NAME_PREFIX + "recovered")));
        assertThat(packetName(packetId)).isEqualTo(NAME_PREFIX + "recovered");
    }

    @Test
    void guestQueriesTripGraphqlReadAtTwoHundredForty() throws Exception {
        String ip = nextIp();
        assertThat(capacityOf("graphql-read", Tier.GUEST)).isEqualTo(240);

        // 240 query fields in 6 requests of 40 aliased fields.
        for (int i = 0; i < 6; i++) {
            assertNoErrors(graphQlOk(ANON, ip, GraphQlRateLimitIT.aliasedReads(40)));
        }
        assertFieldRateLimited(graphQlOk(ANON, ip, GraphQlRateLimitIT.aliasedReads(1)), "graphql-read");

        // Mutations still have their own budget.
        assertNoErrors(graphQlOk(ANON, ip, renameMutation(seedPacket(null), NAME_PREFIX + "w")));

        CLOCK.advance(Duration.ofMinutes(1));
        assertNoErrors(graphQlOk(ANON, ip, GraphQlRateLimitIT.aliasedReads(1)));
    }
}
