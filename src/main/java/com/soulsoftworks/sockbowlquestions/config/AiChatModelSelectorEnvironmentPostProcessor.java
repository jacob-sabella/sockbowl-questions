package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges the app's legacy {@code spring.ai.openai.chat.enabled} /
 * {@code spring.ai.ollama.chat.enabled} switches (still what compose and
 * {@code application.yml} set) onto Spring AI 2.0's actual provider-selection
 * property, {@code spring.ai.model.chat}.
 *
 * <p><b>Why this exists:</b> in Spring AI 1.x, {@code spring.ai.<provider>.chat.enabled}
 * gated whether that provider's {@code ChatModel} autoconfiguration ran. In
 * Spring AI 2.0 this changed (see the 2.0 migration guide): both
 * {@code OpenAiChatAutoConfiguration} and {@code OllamaChatAutoConfiguration}
 * are now gated by {@code @ConditionalOnProperty(name = "spring.ai.model.chat",
 * havingValue = "<provider>", matchIfMissing = true)}. Since this app has both
 * the OpenAI and Ollama starters on the classpath and never set
 * {@code spring.ai.model.chat}, {@code matchIfMissing = true} made <em>both</em>
 * conditions match regardless of the old {@code chat.enabled} flags, so both
 * autoconfigurations ran and registered a {@code ChatModel} bean. That left two
 * candidates for the single {@code ChatModel} parameter of Spring AI's
 * {@code ChatClientAutoConfiguration.chatClientBuilder()}, which failed context
 * refresh with {@code NoUniqueBeanDefinitionException} — the compose
 * crash-loop this class fixes, independent of whichever flags happened to be
 * set.
 *
 * <p>This runs after config data (so it sees the final, env-var-overridden
 * values of the legacy flags) and, unless {@code spring.ai.model.chat} is
 * already set explicitly, derives it:
 * <ul>
 *   <li>only OpenAI enabled -&gt; {@code openai}</li>
 *   <li>only Ollama enabled -&gt; {@code ollama}</li>
 *   <li>both enabled -&gt; whichever {@code sockbowl.ai.provider} names (default {@code openai})</li>
 *   <li>neither enabled -&gt; {@code none} — no {@code ChatModel} bean is created at all,
 *       and generation degrades gracefully at request time (see {@link AiConfig} and
 *       {@code ChatClientFactory}) instead of the app failing to start.</li>
 * </ul>
 *
 * <p>The same {@code matchIfMissing = true} behavior applies to Spring AI 2.0's
 * embedding autoconfigurations, gated by {@code spring.ai.model.embedding}
 * (analogous to {@code spring.ai.model.chat} above). {@code application.yml}
 * sets the legacy {@code spring.ai.openai.embedding.enabled} /
 * {@code spring.ai.ollama.embedding.enabled} flags (openai=false, ollama=true)
 * expecting those to gate embedding autoconfiguration the old way, but since
 * neither sets the new selector property, both {@code OpenAiEmbeddingAutoConfiguration}
 * and {@code OllamaEmbeddingAutoConfiguration} ran regardless, registering two
 * {@code EmbeddingModel} beans and failing
 * {@code Neo4jVectorStoreAutoConfiguration}'s single-{@code EmbeddingModel}
 * dependency with a compose crash-loop identical in shape to the chat one.
 * This class derives {@code spring.ai.model.embedding} from the embedding
 * flags the same way it derives {@code spring.ai.model.chat} from the chat
 * flags.
 */
public class AiChatModelSelectorEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SELECTOR_PROPERTY = "spring.ai.model.chat";
    static final String EMBEDDING_SELECTOR_PROPERTY = "spring.ai.model.embedding";
    private static final String PROPERTY_SOURCE_NAME = "sockbowlAiChatModelSelector";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> derived = new HashMap<>();

        if (!environment.containsProperty(SELECTOR_PROPERTY)) {
            derived.put(SELECTOR_PROPERTY, derive(environment,
                    "spring.ai.openai.chat.enabled", true,
                    "spring.ai.ollama.chat.enabled", false));
        }

        if (!environment.containsProperty(EMBEDDING_SELECTOR_PROPERTY)) {
            derived.put(EMBEDDING_SELECTOR_PROPERTY, derive(environment,
                    "spring.ai.openai.embedding.enabled", false,
                    "spring.ai.ollama.embedding.enabled", true));
        }

        if (!derived.isEmpty()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, derived));
        }
    }

    private String derive(ConfigurableEnvironment environment,
            String openaiFlag, boolean openaiDefault,
            String ollamaFlag, boolean ollamaDefault) {
        boolean openaiEnabled = environment.getProperty(openaiFlag, Boolean.class, openaiDefault);
        boolean ollamaEnabled = environment.getProperty(ollamaFlag, Boolean.class, ollamaDefault);

        if (openaiEnabled && ollamaEnabled) {
            String preferred = environment.getProperty("sockbowl.ai.provider", "openai");
            return "ollama".equalsIgnoreCase(preferred) ? "ollama" : "openai";
        } else if (openaiEnabled) {
            return "openai";
        } else if (ollamaEnabled) {
            return "ollama";
        } else {
            return "none";
        }
    }

    @Override
    public int getOrder() {
        // Must run after application.yml / env vars are loaded so the legacy
        // flags above reflect their final, overridden values.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
