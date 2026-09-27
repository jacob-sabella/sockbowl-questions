package com.soulsoftworks.sockbowlquestions.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test of the flag-to-selector derivation, independent of any
 * Spring AI autoconfiguration. See {@link AiChatClientProviderSelectionTest}
 * for proof that the derived property actually resolves the two-ChatModel-bean
 * crash end to end.
 */
class AiChatModelSelectorEnvironmentPostProcessorTest {

    private final AiChatModelSelectorEnvironmentPostProcessor postProcessor =
            new AiChatModelSelectorEnvironmentPostProcessor();

    private String derive(MockEnvironment env) {
        postProcessor.postProcessEnvironment(env, null);
        return env.getProperty(AiChatModelSelectorEnvironmentPostProcessor.SELECTOR_PROPERTY);
    }

    @Test
    void neitherProviderEnabled_selectsNone() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.enabled", "false")
                .withProperty("spring.ai.ollama.chat.enabled", "false");

        assertThat(derive(env)).isEqualTo("none");
    }

    @Test
    void onlyOpenAiEnabled_selectsOpenAi() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.enabled", "true")
                .withProperty("spring.ai.ollama.chat.enabled", "false");

        assertThat(derive(env)).isEqualTo("openai");
    }

    @Test
    void onlyOllamaEnabled_selectsOllama() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.enabled", "false")
                .withProperty("spring.ai.ollama.chat.enabled", "true");

        assertThat(derive(env)).isEqualTo("ollama");
    }

    @Test
    void bothEnabled_defaultsToOpenAi() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.enabled", "true")
                .withProperty("spring.ai.ollama.chat.enabled", "true");

        assertThat(derive(env)).isEqualTo("openai");
    }

    @Test
    void bothEnabled_honorsSockbowlAiProviderPreference() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.enabled", "true")
                .withProperty("spring.ai.ollama.chat.enabled", "true")
                .withProperty("sockbowl.ai.provider", "ollama");

        assertThat(derive(env)).isEqualTo("ollama");
    }

    @Test
    void neitherFlagSet_defaultsMatchApplicationYml() {
        // application.yml hardcodes openai chat.enabled: true, ollama: false
        // when no compose env vars are present at all.
        assertThat(derive(new MockEnvironment())).isEqualTo("openai");
    }

    @Test
    void explicitSelectorPropertyIsNeverOverridden() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "ollama")
                .withProperty("spring.ai.openai.chat.enabled", "true")
                .withProperty("spring.ai.ollama.chat.enabled", "false");

        assertThat(derive(env)).isEqualTo("ollama");
    }
}
