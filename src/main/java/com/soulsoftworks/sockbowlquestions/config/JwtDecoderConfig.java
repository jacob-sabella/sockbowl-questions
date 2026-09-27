package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

/**
 * The resource server's {@link JwtDecoder} (AUTH-09), active only when
 * {@code sockbowl.auth.enabled=true}.
 *
 * <ul>
 *   <li>Keys come straight from the JWK set URI
 *       ({@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}), so there is
 *       no OIDC discovery call at startup and questions boots even while Keycloak is
 *       still starting. Keys are fetched lazily on the first decode.</li>
 *   <li>Every token must carry the configured issuer ({@code iss}), be within its
 *       validity window, and list {@code sockbowl.auth.audience} (default
 *       {@code sockbowl-api}) in {@code aud}. Keycloak adds that audience through the
 *       {@code sockbowl-api-audience} mapper on the Sockbowl clients, so a token minted
 *       for any other client of the realm is rejected with 401.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${sockbowl.auth.audience:sockbowl-api}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    /**
     * Issuer + timestamp validation (Spring's defaults for an issuer) plus a
     * required-audience check. Public so tests can exercise it without a JWK set.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("sockbowl.auth.audience must not be blank when auth is enabled");
        }
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience)));
    }
}
