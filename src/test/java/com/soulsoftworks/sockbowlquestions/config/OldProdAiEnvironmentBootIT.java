package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7 plan section 3.4, WP-Q1: the full application context boots on the
 * <b>exact old prod env set</b> (recorded as-is from the discovery in
 * {@code plans/m7-deploy.md} section 1.2, carried over per section 4.3/H6)
 * with a dummy {@code OPENAI_API_KEY} and no Ollama server reachable
 * anywhere in this test process.
 *
 * <p>The env var names map onto these properties by Spring Boot's relaxed
 * binding: {@code SPRING_AI_MODEL_CHAT} to {@code spring.ai.model.chat},
 * {@code SPRING_AI_MODEL_EMBEDDING} to {@code spring.ai.model.embedding} and
 * {@code SPRING_AUTOCONFIGURE_EXCLUDE} to {@code spring.autoconfigure.exclude}
 * (a comma list here since a single property value is used, matching how Boot
 * itself accepts the env var). Setting {@code model.chat}/{@code model.embedding}
 * directly (rather than the legacy {@code *.enabled} flags
 * {@link AiChatModelSelectorEnvironmentPostProcessor} otherwise derives them
 * from) means neither {@code OllamaChatAutoConfiguration} nor
 * {@code OllamaEmbeddingAutoConfiguration} even matches its
 * {@code @ConditionalOnProperty}, so no Ollama bean is created and no Ollama
 * connection is ever attempted — proving "no Ollama reachable" holds by
 * construction, not by chance.
 *
 * <p>Excluding {@code Neo4jVectorStoreAutoConfiguration} is required here: its
 * configured index dimension (1024, {@code application.yml}'s Ollama
 * {@code mxbai-embed-large} setting) would not match an OpenAI embedding
 * model's dimension, so old prod never ran it with OpenAI embeddings either.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=openai",
        "spring.ai.model.embedding=openai",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.neo4j.autoconfigure.Neo4jVectorStoreAutoConfiguration",
        "spring.ai.openai.api-key=sk-dummy-not-a-real-key",
})
class OldProdAiEnvironmentBootIT extends Neo4jContainerTestBase {

    @Test
    void contextBootsWithOpenAiOnlyAndNoVectorStore(@Autowired ApplicationContext context) {
        assertThat(context.getBean(OpenAiChatModel.class)).isNotNull();
        assertThat(context.getBean(OpenAiEmbeddingModel.class)).isNotNull();

        assertThatThrownBy(() -> context.getBean(OllamaEmbeddingModel.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> context.getBean("ollamaChatModel"))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> context.getBean(VectorStore.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
