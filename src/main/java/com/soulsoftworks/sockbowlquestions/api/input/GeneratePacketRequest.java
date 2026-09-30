package com.soulsoftworks.sockbowlquestions.api.input;

/**
 * JSON body of {@code POST /api/packets/generate} (D11, plan m4-limits section 2.6).
 * The provider settings stay in headers ({@code X-API-Key}, {@code X-Model},
 * {@code X-Temperature}, ...) so keys never land in request-body logs.
 *
 * @param topic             required; at most {@code sockbowl.ai.max-topic-length} (200) characters
 * @param additionalContext optional; at most {@code sockbowl.ai.max-context-length} (2000) characters
 * @param questionCount     optional, 1 to {@code sockbowl.ai.max-question-count} (30); defaults to
 *                          {@code sockbowl.ai.packetgen.question-count}
 * @param generateBonuses   optional, default {@code true}
 * @param difficultyId      optional; the packet is generated at (and tagged with) this
 *                          difficulty, whose description goes into every prompt
 */
public record GeneratePacketRequest(
        String topic,
        String additionalContext,
        Integer questionCount,
        Boolean generateBonuses,
        String difficultyId) {
}
