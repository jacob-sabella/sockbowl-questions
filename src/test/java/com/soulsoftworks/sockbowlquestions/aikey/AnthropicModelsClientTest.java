package com.soulsoftworks.sockbowlquestions.aikey;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicModelsClientTest {
    private HttpServer server;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"data\":[{\"id\":\"claude-sonnet-5\"},{\"id\":\"claude-opus-5-5\"}],\"has_more\":false}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicModelsClient client() {
        return new AnthropicModelsClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void listsModelIdsAndSendsTheKey() {
        assertThat(client().listModels("sk-ant-1")).containsExactly("claude-sonnet-5", "claude-opus-5-5");
        assertThat(seenKey.get()).isEqualTo("sk-ant-1");
    }

    @Test
    void unauthorizedMeansABadKey() {
        status = 401;
        body = "{\"type\":\"error\"}";

        assertThatThrownBy(() -> client().listModels("sk-ant-bad")).isInstanceOf(InvalidApiRequestException.class);
    }

    @Test
    void serverErrorsAreUpstreamFailures() {
        status = 529;
        body = "{}";

        assertThatThrownBy(() -> client().listModels("sk-ant-1"))
                .isInstanceOf(AnthropicModelsClient.AnthropicUnavailableException.class);
    }
}
