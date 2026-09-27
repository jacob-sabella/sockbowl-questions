package com.soulsoftworks.sockbowlquestions.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTH-09: the {@link JwtDecoderConfig} decoder only accepts tokens that carry
 * {@code aud=sockbowl-api} and the configured issuer.
 *
 * <p>Runs the real bean: a local JWK set endpoint (JDK {@code HttpServer}) serves
 * the public half of a freshly generated RSA key, the decoder is built from
 * {@code jwk-set-uri}, and signed tokens are decoded end to end. That also shows
 * the decoder boots without an OIDC discovery call (the issuer URI here points at
 * nothing).
 */
class JwtAudienceValidationTest {

    private static final String ISSUER = "http://keycloak.invalid/realms/sockbowl";
    private static final String AUDIENCE = "sockbowl-api";

    private static RSAKey signingKey;
    private static HttpServer jwksServer;
    private static String jwkSetUri;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtDecoderConfig.class)
            .withPropertyValues(
                    "sockbowl.auth.enabled=true",
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER);

    @BeforeAll
    static void startJwks() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();
        jwkSetUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/certs";
    }

    @AfterAll
    static void stopJwks() {
        jwksServer.stop(0);
    }

    private void withDecoder(Consumer<JwtDecoder> test, String... extraProps) {
        runner.withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + jwkSetUri)
                .withPropertyValues(extraProps)
                .run(ctx -> test.accept(ctx.getBean(JwtDecoder.class)));
    }

    private static String token(String issuer, List<String> audience) throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("user-1")
                .issuer(issuer)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void right_audience_and_issuer_is_accepted() throws Exception {
        String token = token(ISSUER, List.of("account", AUDIENCE));
        withDecoder(decoder -> {
            Jwt jwt = decoder.decode(token);
            assertThat(jwt.getSubject()).isEqualTo("user-1");
            assertThat(jwt.getAudience()).contains(AUDIENCE);
        });
    }

    @Test
    void single_string_audience_is_accepted() throws Exception {
        String token = token(ISSUER, List.of(AUDIENCE));
        withDecoder(decoder -> assertThat(decoder.decode(token).getSubject()).isEqualTo("user-1"));
    }

    @Test
    void wrong_audience_is_rejected() throws Exception {
        String token = token(ISSUER, List.of("account", "some-other-api"));
        withDecoder(decoder -> assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("aud"));
    }

    @Test
    void missing_audience_is_rejected() throws Exception {
        String token = token(ISSUER, null);
        withDecoder(decoder -> assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class));
    }

    @Test
    void wrong_issuer_is_rejected() throws Exception {
        String token = token("http://evil.invalid/realms/sockbowl", List.of(AUDIENCE));
        withDecoder(decoder -> assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss"));
    }

    @Test
    void audience_is_configurable() throws Exception {
        String token = token(ISSUER, List.of("custom-api"));
        withDecoder(decoder -> assertThat(decoder.decode(token).getSubject()).isEqualTo("user-1"),
                "sockbowl.auth.audience=custom-api");
        String defaultAud = token(ISSUER, List.of(AUDIENCE));
        withDecoder(decoder -> assertThatThrownBy(() -> decoder.decode(defaultAud))
                        .isInstanceOf(JwtValidationException.class),
                "sockbowl.auth.audience=custom-api");
    }

    @Test
    void decoder_is_absent_when_auth_disabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtDecoderConfig.class)
                .withPropertyValues("sockbowl.auth.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class));
    }

    @Test
    void blank_audience_fails_fast() {
        assertThatThrownBy(() -> JwtDecoderConfig.tokenValidator(ISSUER, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
