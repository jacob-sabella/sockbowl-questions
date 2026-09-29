package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.dto.AiProvider;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
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

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = """
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
                     "content":[{"type":"text","text":"hello from claude"}],
                     "stop_reason":"end_turn","stop_sequence":null,
                     "usage":{"input_tokens":3,"output_tokens":4}}
                    """.getBytes(StandardCharsets.UTF_8);
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
                .contains("\"system\":\"**Role:** You are an expert NAQT-style Quizbowl Question Writer");
        verifyNoInteractions(defaultClient);
    }
}
