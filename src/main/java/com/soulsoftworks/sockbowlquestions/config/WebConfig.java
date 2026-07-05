package com.soulsoftworks.sockbowlquestions.config;

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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedOriginPatterns(allowedOrigins)
                .allowedHeaders("*");
    }
}
