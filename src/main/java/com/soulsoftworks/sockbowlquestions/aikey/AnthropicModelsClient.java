package com.soulsoftworks.sockbowlquestions.aikey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists the models an Anthropic API key can use ({@code GET /v1/models}). Doubles
 * as the key check when a key is saved: a key Anthropic rejects is never stored.
 */
@Component
public class AnthropicModelsClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public AnthropicModelsClient(@Value("${sockbowl.ai.anthropic.base-url:https://api.anthropic.com}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    /**
     * @return the model ids the key can use, newest first as Anthropic lists them
     * @throws InvalidApiRequestException when Anthropic rejects the key (400 to the caller)
     * @throws AnthropicUnavailableException when Anthropic can't be reached or errors
     */
    public List<String> listModels(String apiKey) {
        try {
            String raw = restClient.get()
                    .uri("/v1/models?limit=100")
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .onStatus(s -> s.value() == 401 || s.value() == 403, (req, res) -> {
                        throw new InvalidApiRequestException("Anthropic rejected this API key");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new AnthropicUnavailableException("Anthropic answered HTTP " + res.getStatusCode().value());
                    })
                    .body(String.class);
            JsonNode body = raw == null ? null : MAPPER.readTree(raw);
            List<String> ids = new ArrayList<>();
            if (body != null && body.path("data").isArray()) {
                body.path("data").forEach(m -> {
                    String id = m.path("id").asText("");
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                });
            }
            return ids;
        } catch (InvalidApiRequestException | AnthropicUnavailableException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new AnthropicUnavailableException("Anthropic returned an unreadable model list");
        } catch (RestClientResponseException e) {
            throw new AnthropicUnavailableException("Anthropic answered HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new AnthropicUnavailableException("Could not reach Anthropic");
        }
    }

    /** Anthropic itself failed or was unreachable; rendered as 502. */
    public static class AnthropicUnavailableException extends RuntimeException {
        public AnthropicUnavailableException(String message) {
            super(message);
        }
    }
}
