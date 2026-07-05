
package com.soulsoftworks.sockbowlquestions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.service.strategy.QuestionGenerationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Main service for question generation.
 * Delegates to different strategies based on configuration.
 */
@Service
@Slf4j
public class QuestionGenerationService {

    private final Map<String, QuestionGenerationStrategy> strategies;
    private final QuestionGenerationStrategy activeStrategy;

    @Value("${sockbowl.ai.packetgen.question-count:5}")
    private int questionCount;

    @Value("${sockbowl.ai.packetgen.strategy:default}")
    private String strategyName;

    public QuestionGenerationService(
            @Qualifier("defaultStrategy") QuestionGenerationStrategy defaultStrategy,
            @Qualifier("webSearchStrategy") QuestionGenerationStrategy webSearchStrategy,
            @Value("${sockbowl.ai.packetgen.strategy:default}") String configuredStrategy) {

        // Store all available strategies
        this.strategies = Map.of(
                "default", defaultStrategy,
                "web-search", webSearchStrategy
        );

        // Select the active strategy based on configuration
        this.activeStrategy = strategies.getOrDefault(configuredStrategy, defaultStrategy);
        this.strategyName = configuredStrategy;

        log.info("QuestionGenerationService initialized with strategy: {} ({})",
                configuredStrategy, activeStrategy.getClass().getSimpleName());
    }

    /**
     * Generate a complete packet of questions using the active strategy.
     *
     * @param topic Topic for the packet
     * @param additionalContext Additional context or instructions
     * @param questionCount Number of tossups/bonuses to generate (overrides default)
     * @param generateBonuses Whether to generate bonuses (default true)
     * @param requestContext Request context containing optional custom API key and model
     * @return Generated packet
     * @throws JsonProcessingException if JSON processing fails
     */
    public Packet generatePacket(String topic, String additionalContext, int questionCount, boolean generateBonuses, AiRequestContext requestContext) throws JsonProcessingException {
        log.info("Generating packet using strategy: {} with {} questions (bonuses: {})", strategyName, questionCount, generateBonuses);
        return activeStrategy.generatePacket(topic, additionalContext, questionCount, generateBonuses, requestContext);
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
        log.info("Generating tossup using strategy: {}", strategyName);
        return activeStrategy.generateTossup(topic, additionalContext, existingTossups, requestContext);
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