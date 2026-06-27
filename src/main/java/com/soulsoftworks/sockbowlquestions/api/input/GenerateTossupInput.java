package com.soulsoftworks.sockbowlquestions.api.input;

/**
 * Input for the AI-assisted tossup generation hook. {@code apiKey}/{@code model}
 * mirror the X-API-Key / X-Model headers used by the REST generation endpoint so
 * the same security rules apply.
 */
public record GenerateTossupInput(
        String topic,
        String additionalContext,
        String subcategoryId,
        String apiKey,
        String model) {
}
