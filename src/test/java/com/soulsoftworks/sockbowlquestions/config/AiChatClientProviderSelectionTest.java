package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.exception.AiProviderUnavailableException;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the fix for the compose crash-loop: two {@code ChatModel} beans
 * (Ollama + OpenAI) made {@code ChatClientAutoConfiguration.chatClientBuilder()}
 * ambiguous and failed context refresh, regardless of the (ineffective, in
 * Spring AI 2.0) {@code spring.ai.*.chat.enabled} flags — see
 * {@link AiChatModelSelectorEnvironmentPostProcessor}'s javadoc for the full
 * mechanism.
 *
 * <p>Runs only the actual Spring AI autoconfiguration classes plus this app's
 * own {@link AiConfig} and {@link ChatClientFactory} — not a full
 * {@code @SpringBootTest}, which would also require a live Neo4j connection
 * for the vector store's schema initialization (same reasoning as
 * {@code SecurityConfigTest}). The {@link AiChatModelSelectorEnvironmentPostProcessor}
 * is applied via {@code withInitializer}, exactly as {@code SpringApplication}
 * would apply it before context refresh, so this exercises the real
 * production wiring end to end.
 */
class AiChatClientProviderSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(applyAiChatModelSelector())
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    OllamaApiAutoConfiguration.class,
                    OllamaChatAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class))
            .withUserConfiguration(AiConfig.class, ChatClientFactory.class);

    private static ApplicationContextInitializer<ConfigurableApplicationContext> applyAiChatModelSelector() {
        AiChatModelSelectorEnvironmentPostProcessor postProcessor = new AiChatModelSelectorEnvironmentPostProcessor();
        return context -> postProcessor.postProcessEnvironment(context.getEnvironment(), null);
    }

    @Test
    void bothProvidersDisabled_contextStartsAndDegradesGracefully() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.openai.chat.enabled=false",
                        "spring.ai.ollama.chat.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OpenAiChatModel.class);
                    assertThat(context).doesNotHaveBean(OllamaChatModel.class);
                    // AiConfig's chatClient() bean definition is still
                    // registered unconditionally (see its javadoc on why it
                    // can't safely use @ConditionalOnBean), but with no
                    // ChatModel/ChatClient.Builder to inject it resolves to
                    // Spring's internal NullBean marker at creation time
                    // rather than throwing — proven behaviorally below via
                    // ChatClientFactory, since that marker doesn't behave like
                    // a plain null through the ApplicationContext API.
                    ChatClientFactory factory = context.getBean(ChatClientFactory.class);
                    assertThatThrownBy(() -> factory.getChatClient(null))
                            .isInstanceOf(AiProviderUnavailableException.class);
                });
    }

    @Test
    void onlyOpenAiEnabled_contextStartsWithOpenAiChatClient() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.openai.chat.enabled=true",
                        "spring.ai.ollama.chat.enabled=false",
                        "spring.ai.openai.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenAiChatModel.class);
                    assertThat(context).doesNotHaveBean(OllamaChatModel.class);
                    assertThat(context.getBean("quizBowlQuestionWriterChatClient")).isNotNull();

                    ChatClientFactory factory = context.getBean(ChatClientFactory.class);
                    assertThat(factory.getChatClient(null)).isNotNull();
                });
    }

    @Test
    void onlyOllamaEnabled_contextStartsWithOllamaChatClient() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.openai.chat.enabled=false",
                        "spring.ai.ollama.chat.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OllamaChatModel.class);
                    assertThat(context).doesNotHaveBean(OpenAiChatModel.class);
                    assertThat(context.getBean("quizBowlQuestionWriterChatClient")).isNotNull();

                    ChatClientFactory factory = context.getBean(ChatClientFactory.class);
                    assertThat(factory.getChatClient(null)).isNotNull();
                });
    }

    @Test
    void bothProvidersEnabled_contextStartsAndPrefersConfiguredProvider() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.openai.chat.enabled=true",
                        "spring.ai.ollama.chat.enabled=true",
                        "spring.ai.openai.api-key=test-key",
                        "sockbowl.ai.provider=openai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The whole point: exactly one ChatModel bean, never both,
                    // so ChatClientAutoConfiguration's single-ChatModel
                    // parameter is never ambiguous.
                    assertThat(context).hasSingleBean(OpenAiChatModel.class);
                    assertThat(context).doesNotHaveBean(OllamaChatModel.class);
                    assertThat(context.getBean("quizBowlQuestionWriterChatClient")).isNotNull();
                });
    }
}
