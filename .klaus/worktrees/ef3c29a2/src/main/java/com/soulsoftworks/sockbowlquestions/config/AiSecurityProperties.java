package com.soulsoftworks.sockbowlquestions.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for AI security settings.
 */
@Configuration
@ConfigurationProperties(prefix = "sockbowl.ai")
@Data
public class AiSecurityProperties {
    /**
     * Whether to require user-provided API key via X-API-Key header.
     * When true, requests without X-API-Key will be rejected with 400.
     * Default: true (requires users to provide their own API key).
     */
    private boolean requireUserApiKey = true;
}
