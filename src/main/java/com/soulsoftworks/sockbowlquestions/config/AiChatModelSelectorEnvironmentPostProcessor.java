package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

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
 */
public class AiChatModelSelectorEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SELECTOR_PROPERTY = "spring.ai.model.chat";
    private static final String PROPERTY_SOURCE_NAME = "sockbowlAiChatModelSelector";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty(SELECTOR_PROPERTY)) {
            return; // explicit operator override wins
        }

        boolean openaiEnabled = environment.getProperty("spring.ai.openai.chat.enabled", Boolean.class, true);
        boolean ollamaEnabled = environment.getProperty("spring.ai.ollama.chat.enabled", Boolean.class, false);

        String selected;
        if (openaiEnabled && ollamaEnabled) {
            String preferred = environment.getProperty("sockbowl.ai.provider", "openai");
            selected = "ollama".equalsIgnoreCase(preferred) ? "ollama" : "openai";
        } else if (openaiEnabled) {
            selected = "openai";
        } else if (ollamaEnabled) {
            selected = "ollama";
        } else {
            selected = "none";
        }

        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(SELECTOR_PROPERTY, selected)));
    }

    @Override
    public int getOrder() {
        // Must run after application.yml / env vars are loaded so the legacy
        // flags above reflect their final, overridden values.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
