package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.AiConfig;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.AiProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.soulsoftworks.sockbowlquestions.dto.AiProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Factory service for creating ChatClient instances with custom or default configuration.
 * Supports per-request API key and model overrides for OpenAI, and a user's
 * saved Claude key (Anthropic).
 */
@Service
public class ChatClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(ChatClientFactory.class);

    // ObjectProvider (not a plain ChatClient) because the bean is absent when
    // neither AI provider is enabled (see AiConfig); that's a valid runtime
    // state we degrade gracefully for, not a wiring error.
    private final ObjectProvider<ChatClient> defaultChatClientProvider;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;

    @Value("${sockbowl.ai.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    /**
     * Anthropic requires max_tokens, and it caps thinking plus the answer together
     * (Opus 5.5 always thinks). Kept under the Java SDK's non-streaming ceiling (~21k).
     */
    @Value("${sockbowl.ai.anthropic.max-tokens:16000}")
    private int anthropicMaxTokens;

    public ChatClientFactory(
            @Qualifier("quizBowlQuestionWriterChatClient") ObjectProvider<ChatClient> defaultChatClientProvider) {
        this.defaultChatClientProvider = defaultChatClientProvider;
    }

    /**
     * Get ChatClient based on request context.
     * Returns custom client if API key provided, otherwise default.
     *
     * @param context Request context containing optional API key, model, and LLM parameters
     * @return ChatClient configured with appropriate settings
     * @throws AiProviderUnavailableException if no custom API key was supplied and no
     *         default AI provider is configured (both OpenAI and Ollama disabled)
     */
    public ChatClient getChatClient(AiRequestContext context) {
        return getChatClient(context, null);
    }

    /**
     * As {@link #getChatClient(AiRequestContext)}, and on the saved-Claude-key path also
     * constrains the answer to {@code outputSchema} (Anthropic structured outputs), so it
     * is always valid JSON of that shape. Other providers ignore it.
     */
    public ChatClient getChatClient(AiRequestContext context, String outputSchema) {
        if (context == null || !context.hasCustomConfig()) {
            ChatClient defaultChatClient = defaultChatClientProvider.getIfAvailable();
            if (defaultChatClient == null) {
                throw new AiProviderUnavailableException(
                        "No AI chat provider is configured on the server, and no X-API-Key was provided. "
                                + "Enable an AI provider (SPRING_AI_OPENAI_CHAT_ENABLED or SPRING_AI_OLLAMA_CHAT_ENABLED) "
                                + "or supply your own X-API-Key/X-Model headers.");
            }
            logger.debug("Using default ChatClient");
            return defaultChatClient;
        }

        if (context.getProvider() == AiProvider.ANTHROPIC) {
            logger.info("Creating Anthropic ChatClient from the user's saved key - model: {}", context.getModel());
            return createAnthropicChatClient(context, outputSchema);
        }

        logger.info("Creating custom ChatClient with user-provided configuration - model: {}, temp: {}, topP: {}, freqPenalty: {}, presPenalty: {}",
                context.getModel(), context.getTemperature(), context.getTopP(),
                context.getFrequencyPenalty(), context.getPresencePenalty());
        return createCustomChatClient(context);
    }

    /**
     * A ChatClient on the user's saved Claude key. The OpenAI-only sampling knobs
     * (penalties) don't apply, and temperature/top-p are left at Claude's defaults:
     * newer Claude models reject setting both, and the saved-key flow doesn't
     * expose them. Thinking and effort stay at the model's defaults (Opus 5.5: adaptive,
     * medium). The system prompt is identical on every call of a packet, so it's cached
     * (reads cost a tenth of input or less; too short to cache on Haiku, which is harmless).
     */
    private ChatClient createAnthropicChatClient(AiRequestContext context, String outputSchema) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .baseUrl(anthropicBaseUrl)
                .apiKey(context.getApiKey())
                .model(context.getModel())
                .maxTokens(anthropicMaxTokens)
                // Spring AI's default is 60s, too short for a thinking model on a long answer.
                .timeout(Duration.ofMinutes(5))
                .cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.SYSTEM_ONLY).build());
        if (outputSchema != null) {
            builder.outputSchema(outputSchema);
        }
        AnthropicChatOptions options = builder.build();
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .options(options)
                .build();
        return ChatClient.builder(chatModel)
                .defaultSystem(AiConfig.SYSTEM_PROMPT)
                .build();
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
