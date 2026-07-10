package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * Resource-server JWT security, active only when {@code sockbowl.auth.enabled=true}.
 * Tokens are validated by issuer only (no audience check) against
 * {@code KEYCLOAK_ISSUER_URI}. Realm roles from {@code realm_access.roles} are
 * mapped to both a raw authority (e.g. {@code packet:read}) and a
 * {@code ROLE_}-prefixed authority, so {@code @PreAuthorize("hasAuthority('packet:read')")}
 * works directly against Keycloak realm role names.
 *
 * <p>The HTTP layer itself permits every request ({@code anyRequest().permitAll()}) —
 * identical topology to {@link NoSecurityConfig}. The JWT resource-server filter still
 * runs and populates a real {@code JwtAuthenticationToken} when a bearer token is
 * present; when absent, Spring falls back to an anonymous authentication. All actual
 * enforcement lives in {@code @PreAuthorize} via {@code @EnableMethodSecurity}, which
 * structurally guarantees guests are never blocked by a URL-matching regression.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> auth = new ArrayList<>(scopes.convert(jwt));
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles) {
                for (Object r : roles) {
                    if (r != null) {
                        auth.add(new SimpleGrantedAuthority(r.toString()));          // raw permission authority
                        auth.add(new SimpleGrantedAuthority("ROLE_" + r.toString())); // ROLE_ variant
                    }
                }
            }
            return auth;
        });
        return conv;
    }
}
