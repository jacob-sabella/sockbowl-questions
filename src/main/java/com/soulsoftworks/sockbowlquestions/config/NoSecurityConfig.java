package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Guest-mode security wiring: active when {@code sockbowl.auth.enabled=false}
 * (or unset). Permits every request, preserving pre-auth behavior.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
