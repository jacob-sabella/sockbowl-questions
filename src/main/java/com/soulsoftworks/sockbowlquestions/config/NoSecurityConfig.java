package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.ratelimit.RequestGuardFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Guest-mode security wiring: active when {@code sockbowl.auth.enabled=false}
 * (or unset). Permits every request, preserving pre-auth behavior. The M4
 * {@link RequestGuardFilter} still runs (every caller is a guest keyed by IP),
 * so REST rate limits apply with auth off too.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectProvider<RequestGuardFilter> requestGuardFilter) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        requestGuardFilter.ifAvailable(guard -> http.addFilterBefore(guard, AuthorizationFilter.class));
        return http.build();
    }
}
