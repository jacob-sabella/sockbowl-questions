package com.soulsoftworks.sockbowlquestions.api;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlquestions.aikey.UserAiKeyService;
import com.soulsoftworks.sockbowlquestions.api.input.GeneratePacketRequest;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.AiProviderUnavailableException;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitException;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint for AI generation of whole quizbowl packets.
 */
@RestController
@RequestMapping("/api/packets")
public class PacketGenerationController {
    private static final Logger logger = LoggerFactory.getLogger(PacketGenerationController.class);
    private static final int MIN_QUESTION_COUNT = 1;

    private final QuestionGenerationService questionGenerationService;
    private final AiSecurityProperties securityProperties;
    private final UserAiKeyService userAiKeyService;

    @Value("${sockbowl.ai.packetgen.question-count:5}")
    private int defaultQuestionCount;

    public PacketGenerationController(
            QuestionGenerationService questionGenerationService,
            AiSecurityProperties securityProperties,
            UserAiKeyService userAiKeyService) {
        this.questionGenerationService = questionGenerationService;
        this.securityProperties = securityProperties;
        this.userAiKeyService = userAiKeyService;
    }

    /**
     * Generates a complete quizbowl packet (D11: POST with a JSON body; a GET is 405).
     *
     * <p>The call runs inside an {@link com.soulsoftworks.sockbowlquestions.ai.AiGenerationGuard}
     * permit (taken in {@link QuestionGenerationService#generatePacket}): 429
     * {@code rate_limited} ({@code ai-generate}, {@code ai-concurrency}), 429
     * {@code quota_exceeded} ({@code ai.generations}, {@code ai.serverkey}) and 503
     * {@code limiter_unavailable} are rendered by {@code LimitsExceptionAdvice}.
     *
     * @param request topic, additionalContext, questionCount (1-30, default from config), generateBonuses
     * @param apiKey User-provided OpenAI API key (optional, from X-API-Key header); when
     *        absent, the caller's saved Claude key is used if they have one
     * @param model User-provided OpenAI model (optional, from X-Model header)
     * @param temperature Controls randomness (0.0-2.0, default 1.0)
     * @param topP Controls diversity via nucleus sampling (0.0-1.0, default 1.0)
     * @param frequencyPenalty Penalizes token frequency (-2.0 to 2.0, default 0.0)
     * @param presencePenalty Penalizes token presence (-2.0 to 2.0, default 0.0)
     * @return the generated packet as JSON; 400 for invalid input, 502 when the AI
     *         provider itself failed, 503 when no provider is configured
     */
    @PostMapping(path = "generate")
    @PreAuthorize("hasAuthority('question:generate')")
    public ResponseEntity<String> generatePacket(
            @RequestBody(required = false) GeneratePacketRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Model", required = false) String model,
            @RequestHeader(value = "X-Temperature", required = false) Double temperature,
            @RequestHeader(value = "X-Top-P", required = false) Double topP,
            @RequestHeader(value = "X-Frequency-Penalty", required = false) Double frequencyPenalty,
            @RequestHeader(value = "X-Presence-Penalty", required = false) Double presencePenalty,
            @AuthenticationPrincipal Jwt jwt) {

        logger.info("Request received to generate a quizbowl packet");
        // Gated by question:generate, so this is always an authenticated caller when
        // auth is enabled; guest() only occurs when sockbowl.auth.enabled=false.
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt);

        if (request == null) {
            throw new InvalidApiRequestException("A JSON body with at least a topic is required");
        }
        String topic = request.topic();
        String additionalContext = request.additionalContext();
        boolean generateBonuses = request.generateBonuses() == null || request.generateBonuses();
        // Checked here too (not only in the service) so a bad request costs nothing.
        questionGenerationService.validatePrompt(topic, additionalContext);

        // Validate and apply question count limits
        Integer finalQuestionCount = validateQuestionCount(request.questionCount());

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
        // No X-API-Key: fall back to the caller's saved Claude key, if any
        // (X-Model still overrides its saved model).
        if (!requestContext.hasCustomConfig()) {
            requestContext = userAiKeyService.resolveContext(user.keycloakId(), model).orElse(requestContext);
        }

        // Validate request based on security configuration
        validateRequest(requestContext);

        try {
            Packet generatedPacket = questionGenerationService.generatePacket(
                    topic,
                    additionalContext,
                    finalQuestionCount,
                    generateBonuses,
                    requestContext,
                    user.keycloakId(),
                    user.username()
            );

            String resultMessage = generateBonuses
                ? String.format("Successfully generated packet with %d tossups and %d bonuses",
                    finalQuestionCount, finalQuestionCount)
                : String.format("Successfully generated packet with %d tossups (bonuses skipped)",
                    finalQuestionCount);
            logger.info(resultMessage);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Gson().toJson(generatedPacket));
        } catch (LimitException | InvalidApiRequestException e) {
            // Rendered by LimitsExceptionAdvice / GlobalExceptionHandler.
            throw e;
        } catch (AiProviderUnavailableException e) {
            logger.warn("Packet generation requested but no AI provider is available: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getMessage());
        } catch (Exception e) {
            // Log the detail server-side; return a generic message so internal
            // exception text (stack details, upstream API errors) isn't leaked.
            logger.error("Error generating packet", e);
            if (isUpstreamProviderFailure(e)) {
                // The provider (e.g. OpenAI answering 429 or 5xx) failed, not this
                // server: a 502 lets the client tell the two apart.
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("The AI provider could not complete the request. Please try again later.");
            }
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Error generating packet. Please try again or check the server logs.");
        }
    }

    /**
     * Whether a failure came from the AI provider's API (the OpenAI SDK's
     * {@code com.openai.errors.*}, the Anthropic SDK's {@code com.anthropic.errors.*} or Spring AI's retry exceptions) rather than
     * from this service.
     */
    static boolean isUpstreamProviderFailure(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 10; depth++) {
            String name = current.getClass().getName();
            if (name.startsWith("com.openai.errors.") || name.startsWith("com.anthropic.errors.")
                    || name.startsWith("org.springframework.ai.retry.")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
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
        int maxQuestionCount = securityProperties.getMaxQuestionCount();
        if (questionCount < MIN_QUESTION_COUNT) {
            throw new InvalidApiRequestException(
                    String.format("Question count must be at least %d", MIN_QUESTION_COUNT));
        }

        if (questionCount > maxQuestionCount) {
            throw new InvalidApiRequestException(
                    String.format("Question count cannot exceed %d (requested: %d)", maxQuestionCount, questionCount));
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