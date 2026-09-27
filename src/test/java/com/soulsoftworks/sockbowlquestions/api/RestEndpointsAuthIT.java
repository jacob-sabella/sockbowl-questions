package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import com.soulsoftworks.sockbowlquestions.support.KeycloakAuthITBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AUTH-20 (questions), over the real REST HTTP path with real Keycloak tokens:
 * {@code import-random} (AUTH-07/D3, as amended by D15) is open to anyone but gives
 * an ownerless EPHEMERAL packet without {@code packet:create} and an owned DRAFT
 * with it; an invalid bearer is still a 401. {@code /api/packets/generate} still
 * needs a bearer plus the right role. The bank-aggregate GETs stay public. Business
 * logic (bank selection, AI generation) is mocked out — this class proves only the
 * authorization gate, over the wire.
 */
class RestEndpointsAuthIT extends KeycloakAuthITBase {

    @MockitoBean
    private QbreaderImportService importService;

    @MockitoBean
    private QuestionGenerationService questionGenerationService;

    private WebTestClient client() {
        return webTestClientBuilder().build();
    }

    /* ------------------------------ import-random (D15) ------------------------------ */

    @Test
    void importRandomAnonymousGetsAnEphemeralPacket() {
        Packet p = new Packet();
        p.setId("rest-import-packet-anon");
        p.setName("Random");
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                eq(PacketVisibility.EPHEMERAL))).thenReturn(new ImportOutcome(p, List.of()));

        client().post().uri("/api/qbreader/import-random")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"tossupCount\":5,\"bonusCount\":5}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void importRandomPlayerGetsAnEphemeralPacket() {
        Packet p = new Packet();
        p.setId("rest-import-packet-player");
        p.setName("Random");
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                eq(PacketVisibility.EPHEMERAL))).thenReturn(new ImportOutcome(p, List.of()));

        client().post().uri("/api/qbreader/import-random")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(PLAYER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"tossupCount\":5,\"bonusCount\":5}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void importRandomInvalidBearerIs401() {
        client().post().uri("/api/qbreader/import-random")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"tossupCount\":5,\"bonusCount\":5}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void importRandomAuthorSucceedsWithAnOwnedDraft() {
        Packet p = new Packet();
        p.setId("rest-import-packet");
        p.setName("Random");
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                eq(PacketVisibility.defaultForNewPackets()))).thenReturn(new ImportOutcome(p, List.of()));

        client().post().uri("/api/qbreader/import-random")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"tossupCount\":5,\"bonusCount\":5}")
                .exchange()
                .expectStatus().isOk();
    }

    /* -------------------------------- generate ---------------------------------- */

    @Test
    void generateAnonymousIs401() {
        client().get().uri("/api/packets/generate?topic=Science").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void generatePlayerIs403() {
        client().get().uri("/api/packets/generate?topic=Science")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(PLAYER))
                .exchange().expectStatus().isForbidden();
    }

    /**
     * Q2-02: the game backend's service token ({@code packet:read}/{@code packet:read-answers})
     * has no {@code question:generate}, so it's a valid bearer but the wrong role: 403, not 401.
     */
    @Test
    void generateServiceTokenIs403() {
        client().get().uri("/api/packets/generate?topic=Science")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void generateAuthorSucceeds() throws Exception {
        Packet p = new Packet();
        p.setId("rest-generate-packet");
        p.setName("Generated");
        when(questionGenerationService.generatePacket(any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(p);

        client().get().uri("/api/packets/generate?topic=Science")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(AUTHOR))
                .header("X-API-Key", "test-key")
                .header("X-Model", "test-model")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    /* ------------------------------ actuator (Q-M2-06) ----------------------------- */

    /** The compose healthcheck calls this without a token, so it must stay open with auth on. */
    @Test
    void actuatorHealthIsOpenToAnonymousWithAuthOn() {
        client().get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    /* ---------------------------- public bank aggregates -------------------------- */

    @Test
    void bankAggregateEndpointsArePublic() {
        client().get().uri("/api/qbreader/category-counts").exchange().expectStatus().isOk();
        client().get().uri("/api/qbreader/taxonomy-counts").exchange().expectStatus().isOk();
        client().post().uri("/api/qbreader/count")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange().expectStatus().isOk();
    }
}
