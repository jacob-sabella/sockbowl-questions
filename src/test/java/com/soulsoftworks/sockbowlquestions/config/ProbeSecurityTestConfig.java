package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only filter chain for the throwaway {@code /probe/**} controllers
 * ({@link SecurityProbeController}, {@code PacketAuthorizationProbeController}).
 *
 * <p>{@link SecurityConfig} is deny-by-default, so an unlisted path such as
 * {@code /probe/read} never reaches its controller there. The probe suites test
 * something else: the JWT-to-authority mapping and {@code @PreAuthorize} (method
 * security) with the real {@link SecurityConfig} beans. This chain matches only
 * {@code /probe/**}, runs first, uses the same decoder and
 * {@code keycloakJwtAuthenticationConverter}, and leaves every decision to
 * {@code @PreAuthorize}. The URL rules themselves are covered by
 * {@code SecurityConfigTest}'s perimeter tests, which use real endpoints.
 */
@TestConfiguration
public class ProbeSecurityTestConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain probeFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                         JwtAuthenticationConverter keycloakJwtAuthenticationConverter) throws Exception {
        http.securityMatcher("/probe/**")
                .csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(keycloakJwtAuthenticationConverter)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
