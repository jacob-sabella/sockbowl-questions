package com.soulsoftworks.sockbowlquestions.dto;

/** Which provider a BYO-key {@link AiRequestContext} talks to. */
public enum AiProvider {
    /** The per-request {@code X-API-Key} flow (OpenAI-compatible). */
    OPENAI,
    /** A user's saved Claude key (see {@code aikey.UserAiKeyService}). */
    ANTHROPIC
}
