package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.AiConfig;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Factory service for creating ChatClient instances with custom or default configuration.
 * Supports per-request API key and model overrides for OpenAI.
 */
@Service
public class ChatClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(ChatClientFactory.class);

    private final ChatClient defaultChatClient;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;

    public ChatClientFactory(
            @Qualifier("quizBowlQuestionWriterChatClient") ChatClient defaultChatClient) {
        this.defaultChatClient = defaultChatClient;
    }

    /**
     * Get ChatClient based on request context.
     * Returns custom client if API key provided, otherwise default.
     *
     * @param context Request context containing optional API key, model, and LLM parameters
     * @return ChatClient configured with appropriate settings
     */
    public ChatClient getChatClient(AiRequestContext context) {
        if (context == null || !context.hasCustomConfig()) {
            logger.debug("Using default ChatClient");
            return defaultChatClient;
        }

        logger.info("Creating custom ChatClient with user-provided configuration - model: {}, temp: {}, topP: {}, freqPenalty: {}, presPenalty: {}",
                context.getModel(), context.getTemperature(), context.getTopP(),
                context.getFrequencyPenalty(), context.getPresencePenalty());
        return createCustomChatClient(context);
    }

    /**
     * Create a new ChatClient with custom API key, model, and LLM parameters.
     *
     * @param context Request context containing API key, model, and LLM parameters
     * @return ChatClient configured with custom settings
     */
    private ChatClient createCustomChatClient(AiRequestContext context) {
        // Build chat options - in Spring AI 2.0 the OpenAI connection details
        // (API key + base URL) live on the chat options; the model lazily
        // constructs an OpenAIClient from them when no explicit client is set.
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .baseUrl(openAiBaseUrl)
                .apiKey(context.getApiKey())
                .model(context.getModel());

        // Only set optional parameters if they are provided
        if (context.getTemperature() != null) {
            optionsBuilder.temperature(context.getTemperature());
        }
        if (context.getTopP() != null) {
            optionsBuilder.topP(context.getTopP());
        }
        if (context.getFrequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(context.getFrequencyPenalty());
        }
        if (context.getPresencePenalty() != null) {
            optionsBuilder.presencePenalty(context.getPresencePenalty());
        }

        // Create chat model with custom options
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(optionsBuilder.build())
                .build();

        // Build ChatClient with same configuration as default
        return ChatClient.builder(chatModel)
                .defaultSystem(AiConfig.SYSTEM_PROMPT)
                .build();
    }
}
