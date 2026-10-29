package com.example.wallet.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Calls the Anthropic Messages API. Swap this class to change provider; nothing else changes. */
public class AnthropicLlmClient implements LlmClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper om = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public AnthropicLlmClient(String apiKey, String model) { this.apiKey = apiKey; this.model = model; }

    @Override
    public String complete(String system, String user) {
        try {
            String body = om.writeValueAsString(Map.of(
                "model", model, "max_tokens", 400, "temperature", 0, "system", system,
                "messages", List.of(Map.of("role", "user", "content", user))));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("LLM HTTP " + res.statusCode());
            return om.readTree(res.body()).path("content").path(0).path("text").asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("LLM call failed: " + e.getMessage(), e);
        }
    }
}
