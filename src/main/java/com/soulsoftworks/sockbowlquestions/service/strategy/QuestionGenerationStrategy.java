package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
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
     * @param requestContext Request context containing optional custom API key and model
     * @return Generated tossup
     */
    Tossup generateTossup(String topic, String additionalContext, List<Tossup> existingTossups, AiRequestContext requestContext);

    /**
     * Generate a single bonus.
     *
     * @param topic Topic for the bonus
     * @param additionalContext Additional context or instructions
     * @param existingBonuses Previously generated bonuses to avoid duplicates
     * @param existingTossups Previously generated tossups for context
     * @param requestContext Request context containing optional custom API key and model
     * @return Generated bonus
     */
    Bonus generateBonus(String topic, String additionalContext, List<Bonus> existingBonuses, List<Tossup> existingTossups, AiRequestContext requestContext);

    /**
     * Generate a complete packet of questions.
     *
     * @param topic Topic for the packet
     * @param additionalContext Additional context or instructions
     * @param questionCount Number of questions to generate
     * @param generateBonuses Whether to generate bonuses
     * @param requestContext Request context containing optional custom API key and model
     * @return Generated packet
     * @throws JsonProcessingException if JSON processing fails
     */
    Packet generatePacket(String topic, String additionalContext, int questionCount, boolean generateBonuses, AiRequestContext requestContext) throws JsonProcessingException;

    /**
     * Get the name/identifier of this strategy.
     *
     * @return Strategy name
     */
    String getStrategyName();
}
