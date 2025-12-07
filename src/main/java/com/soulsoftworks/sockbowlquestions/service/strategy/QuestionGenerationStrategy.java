package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;

import java.util.List;

/**
 * Strategy interface for different question generation approaches.
 */
public interface QuestionGenerationStrategy {

    /**
     * Generate a single tossup question.
     *
     * @param topic Topic for the question
     * @param additionalContext Additional context or instructions
     * @param existingTossups Previously generated tossups to avoid duplicates
     * @return Generated tossup
     */
    Tossup generateTossup(String topic, String additionalContext, List<Tossup> existingTossups);

    /**
     * Generate a complete packet of questions.
     *
     * @param topic Topic for the packet
     * @param additionalContext Additional context or instructions
     * @param questionCount Number of questions to generate
     * @return Generated packet
     * @throws JsonProcessingException if JSON processing fails
     */
    Packet generatePacket(String topic, String additionalContext, int questionCount) throws JsonProcessingException;

    /**
     * Get the name/identifier of this strategy.
     *
     * @return Strategy name
     */
    String getStrategyName();
}
