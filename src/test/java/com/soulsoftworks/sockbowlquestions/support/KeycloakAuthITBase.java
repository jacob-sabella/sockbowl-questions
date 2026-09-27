package com.soulsoftworks.sockbowlquestions.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * Shared base for the M2 Q3 auth-on integration suites (AUTH-20 questions):
 * real Neo4j ({@link Neo4jContainerTestBase}) plus a real Keycloak Testcontainer,
 * importing {@code keycloak/test-realm.json} (the roles from
 * {@code sockbowl-docker/keycloak/rbac-model.json}, the {@code sockbowl-e2e} and
 * {@code sockbowl-game-backend} clients with the {@code sockbowl-api-audience}
 * mapper, and fixture users for every tier).
 *
 * <p>One static container per JVM (like {@link Neo4jContainerTestBase}), so every
 * subclass shares it and Spring's cached application contexts never point at a
 * stopped container. Subclasses get real bearer tokens via {@link #tokenFor} (the
 * OAuth2 password grant against {@code sockbowl-e2e}, exactly how
 * {@code smoke-auth.sh} and the ng Playwright login do it) and {@link #serviceToken()}
 * for the game backend's service-account token.
 *
 * <p>Fixture user ids are pinned in the realm import so tests can seed
 * {@code Packet.ownerId} deterministically without round-tripping through
 * Keycloak first (a token's {@code sub} claim is the Keycloak-internal user id,
 * not the username).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class KeycloakAuthITBase extends Neo4jContainerTestBase {

    // Keycloak's directory realm import requires the file's basename to match
    // "<realm>-realm.json"; our fixture file is keycloak/test-realm.json, so the
    // realm inside it must be named "test".
    private static final String REALM = "test";
    private static final String E2E_CLIENT_ID = "sockbowl-e2e";
    private static final String NO_AUDIENCE_CLIENT_ID = "sockbowl-no-audience";
    private static final String BACKEND_CLIENT_ID = "sockbowl-game-backend";
    private static final String BACKEND_CLIENT_SECRET = "test-backend-secret";
    private static final String PASSWORD = "password";

    /** Usernames matching {@code keycloak/test-realm.json}. */
    public static final String PLAYER = "player";
    public static final String AUTHOR = "author";
    public static final String AUTHOR2 = "author2";
    public static final String MODERATOR = "moderator";
    public static final String ADMIN = "admin";

    /** Fixed Keycloak user ids (the JWT {@code sub} of each fixture user), pinned in the realm import. */
    public static final String PLAYER_SUB = "11111111-1111-1111-1111-111111111111";
    public static final String AUTHOR_SUB = "22222222-2222-2222-2222-222222222222";
    public static final String AUTHOR2_SUB = "33333333-3333-3333-3333-333333333333";
    public static final String MODERATOR_SUB = "44444444-4444-4444-4444-444444444444";
    public static final String ADMIN_SUB = "55555555-5555-5555-5555-555555555555";

    protected static final KeycloakContainer KEYCLOAK = new KeycloakContainer()
            .withRealmImportFile("keycloak/test-realm.json");

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.auth.enabled", () -> "true");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK.getIssuerUrl(REALM));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> KEYCLOAK.getJwksUri(REALM));
    }

    @LocalServerPort
    protected int port;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** A bearer token for a fixture user (password grant on {@code sockbowl-e2e}, like the real e2e login). */
    protected static String tokenFor(String username) {
        return KEYCLOAK.getAccessToken(REALM, E2E_CLIENT_ID, username, PASSWORD);
    }

    /** The game backend's service-account token: {@code packet:read} + {@code packet:read-answers}. */
    protected static String serviceToken() {
        return KEYCLOAK.getClientCredentialsToken(REALM, BACKEND_CLIENT_ID, BACKEND_CLIENT_SECRET);
    }

    /**
     * A token from a client with no {@code sockbowl-api-audience} mapper (AudienceIT):
     * valid signature and issuer, but missing the required {@code aud}.
     */
    protected static String tokenWithoutAudience(String username) {
        return KEYCLOAK.getAccessToken(REALM, NO_AUDIENCE_CLIENT_ID, username, PASSWORD);
    }

    protected WebTestClient.Builder webTestClientBuilder() {
        return WebTestClient.bindToServer().baseUrl(baseUrl()).responseTimeout(Duration.ofSeconds(20));
    }

    /** A {@code POST /graphql} tester, optionally bearing a token (null = anonymous). */
    protected HttpGraphQlTester graphQlTester(String bearerTokenOrNull) {
        // HttpGraphQlTester.Builder#url(String) forwards straight to
        // WebTestClient.Builder#baseUrl(String), replacing (not resolving against) the
        // baseUrl() the builder already carries from webTestClientBuilder() below. A
        // relative "/graphql" would silently become the *whole* base url, so WebClient
        // falls back to localhost:80 instead of the random server port. Pass the full
        // absolute URL instead.
        HttpGraphQlTester.Builder<?> builder = HttpGraphQlTester.builder(webTestClientBuilder()).url(baseUrl() + "/graphql");
        if (bearerTokenOrNull != null) {
            builder = builder.header("Authorization", "Bearer " + bearerTokenOrNull);
        }
        return builder.build();
    }
}
