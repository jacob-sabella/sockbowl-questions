package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitErrorResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebMvc
public class WebConfig implements WebMvcConfigurer {

    /**
     * Allowed CORS origins, driven by SOCKBOWL_ALLOWED_ORIGINS (wired in
     * docker-compose). Defaults to localhost for dev. Previously hardcoded to
     * "*", which let any origin invoke the destructive authoring mutations.
     */
    @Value("${sockbowl.cors.allowed-origins:http://localhost,http://localhost:80}")
    private String[] allowedOrigins;

    /**
     * M4: browsers (ng) may read {@code Retry-After} and the {@code X-RateLimit-*}
     * headers of a 429, which the request guard writes after the CORS filter has
     * added the allow-origin header.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedOriginPatterns(allowedOrigins)
                .allowedHeaders("*")
                .exposedHeaders(LimitErrorResponses.EXPOSED_HEADERS.toArray(String[]::new));
    }
}
