package com.soulsoftworks.sockbowlquestions.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for AI security settings ({@code sockbowl.ai.*}),
 * including M4's AI cost controls (D11, plan m4-limits section 2.6), which
 * {@link com.soulsoftworks.sockbowlquestions.ai.AiGenerationGuard} enforces.
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

    /** Limits on calls that run on the server's own provider key (no X-API-Key). */
    private ServerKey serverKey = new ServerKey();

    /** Longest accepted generation {@code topic}. */
    private int maxTopicLength = 200;

    /** Longest accepted {@code additionalContext}. */
    private int maxContextLength = 2000;

    /** Largest {@code questionCount} for one packet generation. */
    private int maxQuestionCount = 30;

    /**
     * TTL of the {@code ai:inflight:{sub}} concurrency lock: a safety net that
     * frees the lock if the holder dies without releasing it. Longer than the
     * 10-minute request timeout, so a live generation never loses its lock.
     */
    private Duration inflightTtl = Duration.ofSeconds(660);

    /** {@code Retry-After} given to a second concurrent generation by the same caller. */
    private Duration concurrencyRetryAfter = Duration.ofSeconds(30);

    @Data
    public static class ServerKey {
        /** D11: server-key generations allowed per UTC day, across all users (fails closed). */
        private long dailyBudget = 200;

        /**
         * Models a server-key call may name in {@code X-Model} / {@code model}.
         * A call that names no model runs the server's default. Empty means no
         * model may be named. Calls with their own key (BYO) may use any model.
         */
        private List<String> allowedModels = new ArrayList<>();
    }
}
