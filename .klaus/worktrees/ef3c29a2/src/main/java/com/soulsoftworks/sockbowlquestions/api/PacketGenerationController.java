package com.soulsoftworks.sockbowlquestions.api;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for testing the Quizbowl packet generation functionality.
 * Provides endpoints to generate and validate quizbowl packets.
 */
@RestController
@RequestMapping("/api/packets")
public class PacketGenerationController {
    private static final Logger logger = LoggerFactory.getLogger(PacketGenerationController.class);
    private static final int MAX_QUESTION_COUNT = 30;
    private static final int MIN_QUESTION_COUNT = 1;

    private final QuestionGenerationService questionGenerationService;
    private final AiSecurityProperties securityProperties;

    @Value("${sockbowl.ai.packetgen.question-count:5}")
    private int defaultQuestionCount;

    public PacketGenerationController(
            QuestionGenerationService questionGenerationService,
            AiSecurityProperties securityProperties) {
        this.questionGenerationService = questionGenerationService;
        this.securityProperties = securityProperties;
    }

    /**
     * Generates a complete quizbowl packet.
     *
     * @param topic Topic for the packet
     * @param additionalContext Additional context or instructions
     * @param questionCount Number of tossups/bonuses to generate (1-30, default from config)
     * @param generateBonuses Whether to generate bonuses (default true)
     * @param apiKey User-provided OpenAI API key (optional, from X-API-Key header)
     * @param model User-provided OpenAI model (optional, from X-Model header)
     * @param temperature Controls randomness (0.0-2.0, default 1.0)
     * @param topP Controls diversity via nucleus sampling (0.0-1.0, default 1.0)
     * @param frequencyPenalty Penalizes token frequency (-2.0 to 2.0, default 0.0)
     * @param presencePenalty Penalizes token presence (-2.0 to 2.0, default 0.0)
     * @return ResponseEntity containing the generated packet as text
     */
    @GetMapping(path = "generate", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> generatePacket(
            @RequestParam String topic,
            @RequestParam(required = false) String additionalContext,
            @RequestParam(required = false) Integer questionCount,
            @RequestParam(required = false, defaultValue = "true") Boolean generateBonuses,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Model", required = false) String model,
            @RequestHeader(value = "X-Temperature", required = false) Double temperature,
            @RequestHeader(value = "X-Top-P", required = false) Double topP,
            @RequestHeader(value = "X-Frequency-Penalty", required = false) Double frequencyPenalty,
            @RequestHeader(value = "X-Presence-Penalty", required = false) Double presencePenalty) {

        logger.info("Request received to generate a quizbowl packet");

        // Validate and apply question count limits
        Integer finalQuestionCount = validateQuestionCount(questionCount);

        // Build request context from headers
        AiRequestContext.AiRequestContextBuilder contextBuilder = AiRequestContext.builder()
                .apiKey(apiKey)
                .model(model);

        // Add optional LLM parameters if provided
        if (temperature != null) {
            contextBuilder.temperature(temperature);
        }
        if (topP != null) {
            contextBuilder.topP(topP);
        }
        if (frequencyPenalty != null) {
            contextBuilder.frequencyPenalty(frequencyPenalty);
        }
        if (presencePenalty != null) {
            contextBuilder.presencePenalty(presencePenalty);
        }

        AiRequestContext requestContext = contextBuilder.build();

        // Validate request based on security configuration
        validateRequest(requestContext);

        try {
            Packet generatedPacket = questionGenerationService.generatePacket(
                    topic,
                    additionalContext,
                    finalQuestionCount,
                    generateBonuses,
                    requestContext
            );

            String resultMessage = generateBonuses
                ? String.format("Successfully generated packet with %d tossups and %d bonuses",
                    finalQuestionCount, finalQuestionCount)
                : String.format("Successfully generated packet with %d tossups (bonuses skipped)",
                    finalQuestionCount);
            logger.info(resultMessage);

            return ResponseEntity.ok(new Gson().toJson(generatedPacket));
        } catch (Exception e) {
            logger.error("Error generating packet", e);
            return ResponseEntity.internalServerError().body("Error generating packet: " + e.getMessage());
        }
    }

    /**
     * Validates and applies limits to question count parameter.
     *
     * @param questionCount User-provided question count (nullable)
     * @return Validated question count within bounds
     * @throws InvalidApiRequestException if question count is out of bounds
     */
    private Integer validateQuestionCount(Integer questionCount) {
        // Use default if not provided
        if (questionCount == null) {
            logger.debug("No question count provided, using default: {}", defaultQuestionCount);
            return defaultQuestionCount;
        }

        // Validate bounds
        if (questionCount < MIN_QUESTION_COUNT) {
            throw new InvalidApiRequestException(
                    String.format("Question count must be at least %d", MIN_QUESTION_COUNT));
        }

        if (questionCount > MAX_QUESTION_COUNT) {
            throw new InvalidApiRequestException(
                    String.format("Question count cannot exceed %d (requested: %d)", MAX_QUESTION_COUNT, questionCount));
        }

        logger.info("Using requested question count: {}", questionCount);
        return questionCount;
    }

    /**
     * Validates the API request based on security configuration.
     *
     * @param context Request context containing API key and model
     * @throws InvalidApiRequestException if validation fails
     */
    private void validateRequest(AiRequestContext context) {
        // Rule 1: If require-user-api-key=true, API key is mandatory
        if (securityProperties.isRequireUserApiKey() && !context.hasCustomConfig()) {
            throw new InvalidApiRequestException(
                    "API key is required. Please provide X-API-Key header.");
        }

        // Rule 2: If API key provided, model must also be provided
        if (context.hasCustomConfig() && !context.isComplete()) {
            throw new InvalidApiRequestException(
                    "When providing X-API-Key header, X-Model header is also required.");
        }
    }

}