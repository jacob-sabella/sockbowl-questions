
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

/**
 * Main service for question generation.
 * Delegates to different strategies based on configuration.
 */
@Service
@Slf4j
public class QuestionGenerationService {

    private final QuestionGenerationStrategy activeStrategy;
    private final String strategyName;

    @Value("${sockbowl.ai.packetgen.question-count:5}")
    private int questionCount;

    public QuestionGenerationService(
            @Qualifier("defaultStrategy") QuestionGenerationStrategy defaultStrategy) {
        this.activeStrategy = defaultStrategy;
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