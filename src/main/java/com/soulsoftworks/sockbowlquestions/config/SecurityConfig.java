package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.api.AdminUsageController;
import com.soulsoftworks.sockbowlquestions.ratelimit.RequestGuardFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * Resource-server JWT security, active only when {@code sockbowl.auth.enabled=true}.
 *
 * <p>Tokens are decoded by {@link JwtDecoderConfig} (JWK set URI, issuer and
 * {@code aud=sockbowl-api} validation). Realm roles from {@code realm_access.roles}
 * are mapped to both a raw authority (e.g. {@code packet:create}) and a
 * {@code ROLE_}-prefixed authority, so {@code @PreAuthorize("hasAuthority('packet:create')")}
 * works directly against Keycloak realm role names.
 *
 * <p><strong>Deny by default</strong> (AUTH-08). Every route is listed explicitly;
 * anything else is {@code denyAll()} (401 anonymous, 403 authenticated):
 * <ul>
 *   <li>{@code POST /graphql}: open at the URL level. Authorization is field level:
 *       {@code @PreAuthorize} on every mutation, and the read policy on the packet
 *       queries.</li>
 *   <li>Bank aggregates ({@code GET /api/qbreader/{stats,dimensions,category-counts,taxonomy-counts}},
 *       {@code POST /api/qbreader/count}): open. They return counts only.</li>
 *   <li>{@code POST /api/qbreader/import-random}: open (D15 amends D3). Callers with
 *       {@code packet:create} get an owned DRAFT packet; everyone else gets an
 *       ownerless, game-only EPHEMERAL one. Counts are clamped; M4 rate-limits it.</li>
 *   <li>{@code /api/packets/generate}: authenticated, plus {@code question:generate}
 *       on the method.</li>
 *   <li>{@code /actuator/health/**}: open, for the compose healthcheck.</li>
 *   <li>GraphiQL: open only when {@code spring.graphql.graphiql.enabled=true}.</li>
 * </ul>
 * Error and async re-dispatches are permitted because the original request was
 * already authorized; without that a 400 would turn into a 401/403 on the
 * {@code /error} forward.
 *
 * <p>The M4 {@link RequestGuardFilter} (IP/subject bans and REST rate limits)
 * runs just before {@link AuthorizationFilter}: after bearer authentication, so
 * the caller's {@code sub} and tier are known, and before any authorization
 * decision. A request with an invalid bearer is answered 401 by the resource
 * server before it reaches the guard, so it is not rate limited (the failed
 * decode is its cost). {@link NoSecurityConfig} adds the same filter instance.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class SecurityConfig {

    static final String[] PUBLIC_BANK_GETS = {
            "/api/qbreader/stats",
            "/api/qbreader/dimensions",
            "/api/qbreader/category-counts",
            "/api/qbreader/taxonomy-counts"
    };

    @Value("${spring.graphql.graphiql.enabled:false}")
    private boolean graphiqlEnabled;

    @Value("${spring.graphql.graphiql.path:/graphiql}")
    private String graphiqlPath;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                           ObjectProvider<RequestGuardFilter> requestGuardFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> {
                    a.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll();
                    a.requestMatchers(HttpMethod.POST, "/graphql").permitAll();
                    a.requestMatchers(HttpMethod.GET, PUBLIC_BANK_GETS).permitAll();
                    a.requestMatchers(HttpMethod.POST, "/api/qbreader/count").permitAll();
                    // D15: guests and players get an EPHEMERAL packet, packet:create an owned DRAFT.
                    a.requestMatchers(HttpMethod.POST, "/api/qbreader/import-random").permitAll();
                    a.requestMatchers("/api/packets/generate").authenticated();
                    // Saved Claude key (plus question:generate, checked on the controller).
                    a.requestMatchers("/api/me/ai-key", "/api/me/ai-key/**").authenticated();
                    // M4-AD-01: game's admin usage API relays the admin's own token here.
                    a.requestMatchers(HttpMethod.GET, "/api/admin/usage/content-counts")
                            .hasAuthority(AdminUsageController.ADMIN_ACCESS);
                    a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    if (graphiqlEnabled) {
                        a.requestMatchers(HttpMethod.GET, graphiqlPath, graphiqlPath + "/**").permitAll();
                    }
                    a.anyRequest().denyAll();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(keycloakJwtAuthenticationConverter())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        // M4 request guard (bans + REST rate limits). Always present in the
        // application context (RequestGuardFilterConfig); absent only in
        // @WebMvcTest slices that import this class alone.
        requestGuardFilter.ifAvailable(guard -> http.addFilterBefore(guard, AuthorizationFilter.class));
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
