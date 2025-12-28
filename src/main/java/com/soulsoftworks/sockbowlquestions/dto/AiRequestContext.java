package com.soulsoftworks.sockbowlquestions.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Encapsulates user-provided API configuration that flows through the request lifecycle.
 * Supports per-request API key and model overrides for OpenAI.
 */
@Data
@Builder
public class AiRequestContext {
    /**
     * User-provided OpenAI API key (from X-API-Key header).
     */
    private String apiKey;

    /**
     * User-provided OpenAI model name (from X-Model header).
     */
    private String model;

    /**
     * Temperature controls randomness in responses.
     * Range: 0.0 to 2.0
     * - 0.0: Deterministic, focused responses
     * - 1.0: Balanced
     * - 2.0: Maximum creativity and randomness
     * If not provided, OpenAI's default will be used.
     */
    private Double temperature;

    /**
     * Top-P (nucleus sampling) controls diversity of token selection.
     * Range: 0.0 to 1.0
     * - Lower values: More focused and deterministic
     * - 1.0: Consider all tokens
     * Alternative to temperature; use one or the other, not both.
     * If not provided, OpenAI's default will be used.
     */
    private Double topP;

    /**
     * Frequency penalty penalizes tokens based on their frequency in the text so far.
     * Range: -2.0 to 2.0
     * - Positive values: Decrease likelihood of repeating the same phrases
     * - 0.0: No penalty
     * - Negative values: Encourage repetition
     * If not provided, OpenAI's default will be used.
     */
    private Double frequencyPenalty;

    /**
     * Presence penalty penalizes tokens that have already appeared in the text.
     * Range: -2.0 to 2.0
     * - Positive values: Encourage talking about new topics
     * - 0.0: No penalty
     * - Negative values: Encourage staying on topic
     * If not provided, OpenAI's default will be used.
     */
    private Double presencePenalty;

    /**
     * Check if custom API configuration is provided.
     *
     * @return true if API key is provided and not blank
     */
    public boolean hasCustomConfig() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Check if custom configuration is complete (both API key and model provided).
     *
     * @return true if both API key and model are provided and not blank
     */
    public boolean isComplete() {
        return hasCustomConfig() && model != null && !model.isBlank();
    }
}
