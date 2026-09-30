package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.dto.AiProvider;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** A saved-Claude-key context really talks to the Anthropic Messages API with that key and model. */
class ChatClientFactoryAnthropicTest {
    private HttpServer server;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile String content = "[{\"type\":\"text\",\"text\":\"hello from claude\"}]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = """
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
                     "content":%s,
                     "stop_reason":"end_turn","stop_sequence":null,
                     "usage":{"input_tokens":3,"output_tokens":4}}
                    """.formatted(content).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedClaudeKeyCallsAnthropicWithTheKeyAndModel() {
        ObjectProvider<ChatClient> defaultClient = mock(ObjectProvider.class);
        ChatClientFactory factory = new ChatClientFactory(defaultClient);
        ReflectionTestUtils.setField(factory, "anthropicBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(factory, "anthropicMaxTokens", 8192);

        ChatClient client = factory.getChatClient(AiRequestContext.builder()
                .provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").model("claude-sonnet-5").build());
        String answer = client.prompt().user("hi").call().content();

        assertThat(answer).isEqualTo("hello from claude");
        assertThat(seenKey.get()).isEqualTo("sk-ant-test");
        assertThat(seenBody.get()).contains("\"model\":\"claude-sonnet-5\"").contains("\"max_tokens\":8192")
                .contains("\"system\":[{\"text\":\"**Role:** You are an expert NAQT-style Quizbowl Question Writer");
        verifyNoInteractions(defaultClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void thinkingBlocksAreSkippedAndTheAnswerIsSchemaConstrainedWithACachedSystemPrompt() {
        // Opus 5.5 always thinks: the thinking block comes first, the answer last.
        content = """
                [{"type":"thinking","thinking":"let me plan the clues","signature":"sig"},
                 {"type":"redacted_thinking","data":"opaque"},
                 {"type":"text","text":"{\\"question\\":\\"q\\",\\"answer\\":\\"a\\"}"}]""";
        ChatClientFactory factory = new ChatClientFactory(mock(ObjectProvider.class));
        ReflectionTestUtils.setField(factory, "anthropicBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(factory, "anthropicMaxTokens", 16000);

        String schema = "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}},"
                + "\"required\":[\"question\"],\"additionalProperties\":false}";
        ChatClient client = factory.getChatClient(AiRequestContext.builder()
                .provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").model("claude-opus-5-5").build(), schema);
        ChatResponse response = client.prompt().user("hi").call().chatResponse();

        assertThat(LlmResponses.answerText(response)).isEqualTo("{\"question\":\"q\",\"answer\":\"a\"}");
        assertThat(seenBody.get()).contains("\"json_schema\"").contains("\"additionalProperties\":false")
                .contains("\"cache_control\"").contains("\"max_tokens\":16000");
    }
}
