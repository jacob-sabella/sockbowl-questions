package com.soulsoftworks.sockbowlquestions.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.Map;

/** Reading the model's answer out of a {@link ChatResponse}. */
public final class LlmResponses {

    private LlmResponses() {
    }

    /**
     * The answer text of a response: every text generation joined, skipping thinking
     * blocks. Spring AI 2.0.1's Anthropic model turns each thinking block into its own
     * Generation ahead of the answer (metadata "signature", or "data" when redacted),
     * so {@code ChatClient...content()}, which reads only the first Generation, returns
     * the thinking instead of the answer on models that think (Opus 5.5 always does).
     */
    public static String answerText(ChatResponse response) {
        if (response == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message == null || message.getText() == null) {
                continue;
            }
            Map<String, Object> metadata = message.getMetadata();
            if (metadata.containsKey("signature") || metadata.containsKey("data")) {
                continue;
            }
            text.append(message.getText());
        }
        return text.toString();
    }
}
