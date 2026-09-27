package com.soulsoftworks.sockbowlquestions.exception;

/**
 * Thrown when a caller asks for generation but no AI chat provider is
 * configured (neither OpenAI nor Ollama enabled, and no per-request API key
 * supplied). Distinct from a generic failure so callers get a clear,
 * actionable response instead of a bare 500.
 */
public class AiProviderUnavailableException extends RuntimeException {
    public AiProviderUnavailableException(String message) {
        super(message);
    }
}
