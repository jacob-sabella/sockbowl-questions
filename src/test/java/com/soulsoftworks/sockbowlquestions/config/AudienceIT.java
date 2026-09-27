package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.support.KeycloakAuthITBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * AUTH-09/AUTH-20: a token minted by a real Keycloak, with a valid signature and
 * issuer but from a client with no {@code sockbowl-api-audience} mapper, is rejected
 * with 401 on both the REST and GraphQL perimeters. {@link JwtAudienceValidationTest}
 * proves the {@link JwtDecoderConfig} validator in isolation with hand-signed tokens;
 * this is the end-to-end proof with a real Keycloak-issued one.
 */
class AudienceIT extends KeycloakAuthITBase {

    @Test
    void tokenWithoutAudienceIsRejectedOnGraphQl() {
        webTestClientBuilder().build().post().uri("/graphql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutAudience(PLAYER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ getAllDifficulties { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithoutAudienceIsRejectedOnRest() {
        webTestClientBuilder().build().post().uri("/api/qbreader/import-random")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutAudience(AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"tossupCount\":5,\"bonusCount\":5}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validAudienceTokenIsAcceptedForComparison() {
        // Same shape of request, but a token minted through sockbowl-e2e (which carries
        // aud=sockbowl-api): authentication succeeds, so this never sees 401.
        webTestClientBuilder().build().post().uri("/graphql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(PLAYER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ getAllDifficulties { id } }\"}")
                .exchange()
                .expectStatus().isOk();
    }
}
