
package com.soulsoftworks.sockbowlquestions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.ai.AiGenerationGuard;
import com.soulsoftworks.sockbowlquestions.ai.AiPermit;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.service.strategy.QuestionGenerationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Main service for question generation.
 * Delegates to different strategies based on configuration.
 *
 * <p>M4 (D11, plan m4-limits section 2.6): every generation entry point here runs
 * inside an {@link AiGenerationGuard} permit, so the rate limit, the concurrency
 * lock, the per-user quota and the global server-key budget apply to the REST
 * endpoint and to GraphQL {@code generateAndAddTossup} alike. A generation that
 * throws is refunded when its permit closes.
 */
@Service
@Slf4j
public class QuestionGenerationService {

    private final QuestionGenerationStrategy activeStrategy;
    private final String strategyName;
    private final AiGenerationGuard aiGenerationGuard;
    private final AiSecurityProperties aiProperties;

    public QuestionGenerationService(
            @Qualifier("defaultStrategy") QuestionGenerationStrategy defaultStrategy,
            AiGenerationGuard aiGenerationGuard,
            AiSecurityProperties aiProperties) {
        this.activeStrategy = defaultStrategy;
        this.aiGenerationGuard = aiGenerationGuard;
        this.aiProperties = aiProperties;
        this.strategyName = defaultStrategy.getStrategyName();

        log.info("QuestionGenerationService initialized with strategy: {} ({})",
                strategyName, activeStrategy.getClass().getSimpleName());
    }

    /**
     * Generate a complete packet of questions using the active strategy.
     *
     * @param topic Topic for the packet
     * @param additionalContext Additional context or instructions
     * @param questionCount Number of tossups/bonuses to generate (overrides default)
     * @param generateBonuses Whether to generate bonuses (default true)
     * @param requestContext Request context containing optional custom API key and model
     * @param ownerId Keycloak sub of the requesting user, null if anonymous (auth disabled)
     * @param ownerDisplayName preferred_username of the requesting user, null if anonymous
     * @return Generated packet
     * @throws JsonProcessingException if JSON processing fails
     */
    public Packet generatePacket(String topic, String additionalContext, int questionCount, boolean generateBonuses,
                                  AiRequestContext requestContext, String ownerId, String ownerDisplayName) throws JsonProcessingException {
        validatePrompt(topic, additionalContext);
        log.info("Generating packet using strategy: {} with {} questions (bonuses: {})", strategyName, questionCount, generateBonuses);
        try (AiPermit permit = aiGenerationGuard.acquire(requestContext)) {
            Packet packet = activeStrategy.generatePacket(topic, additionalContext, questionCount, generateBonuses,
                    requestContext, ownerId, ownerDisplayName);
            permit.success((long) questionCount * (generateBonuses ? 2 : 1));
            return packet;
        }
    }

    /**
     * Generate a single tossup using the active strategy.
     *
     * @param topic Topic for the question
     * @param additionalContext Additional context or instructions
     * @param existingTossups Previously generated tossups to avoid duplicates
     * @param requestContext Request context containing optional custom API key and model
     * @return Generated tossup
     */
    public Tossup generateTossup(String topic, String additionalContext, List<Tossup> existingTossups, AiRequestContext requestContext) {
        validatePrompt(topic, additionalContext);
        log.info("Generating tossup using strategy: {}", strategyName);
        try (AiPermit permit = aiGenerationGuard.acquire(requestContext)) {
            Tossup tossup = activeStrategy.generateTossup(topic, additionalContext, existingTossups, requestContext);
            if (tossup != null) {
                permit.success(1);
            }
            return tossup;
        }
    }

    /**
     * Rejects prompt text over the configured lengths before anything is charged
     * ({@code sockbowl.ai.max-topic-length} / {@code max-context-length}).
     *
     * @throws InvalidApiRequestException when the topic is blank or either field is too long
     */
    public void validatePrompt(String topic, String additionalContext) {
        if (topic == null || topic.isBlank()) {
            throw new InvalidApiRequestException("Topic is required");
        }
        if (topic.length() > aiProperties.getMaxTopicLength()) {
            throw new InvalidApiRequestException(String.format(
                    "Topic cannot exceed %d characters (got %d)", aiProperties.getMaxTopicLength(), topic.length()));
        }
        if (additionalContext != null && additionalContext.length() > aiProperties.getMaxContextLength()) {
            throw new InvalidApiRequestException(String.format(
                    "Additional context cannot exceed %d characters (got %d)",
                    aiProperties.getMaxContextLength(), additionalContext.length()));
        }
    }

    /**
     * Get the currently active strategy name.
     *
     * @return Strategy name
     */
    public String getActiveStrategyName() {
        return strategyName;
    }

    /**
     * Get the active strategy instance.
     *
     * @return Active strategy
     */
    public QuestionGenerationStrategy getActiveStrategy() {
        return activeStrategy;
    }

}