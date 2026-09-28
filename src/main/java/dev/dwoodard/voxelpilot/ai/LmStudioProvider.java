package dev.dwoodard.voxelpilot.ai;

import com.google.gson.*;
import dev.dwoodard.voxelpilot.config.ProviderConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class LmStudioProvider implements ModelProvider {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ProviderConfig config;

    public LmStudioProvider(ProviderConfig config) { this.config = config; }

    @Override public String id() { return "lm-studio"; }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return listModels().thenApply(models -> !models.isEmpty()).exceptionally(e -> false);
    }

    @Override
    public CompletableFuture<List<String>> listModels() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/v1/models")).GET().timeout(Duration.ofSeconds(10));
        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LM Studio HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonArray data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
            List<String> models = new ArrayList<>();
            if (data != null) for (JsonElement element : data) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("id")) models.add(obj.get("id").getAsString());
            }
            return models;
        });
    }

    @Override
    public CompletableFuture<String> stream(List<ChatMessage> messages, Consumer<String> onLine) {
        String systemPrompt = "";
        String userInput = "";

        for (ChatMessage m : messages) {
            if ("system".equals(m.role())) {
                systemPrompt = m.content();
            } else if ("user".equals(m.role())) {
                userInput = m.content();
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        if (!systemPrompt.isEmpty()) body.addProperty("system_prompt", systemPrompt);
        body.addProperty("input", userInput);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 3000);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/v1/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));

        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LM Studio HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonObject responseObj = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray output = responseObj.getAsJsonArray("output");
            if (output == null || output.isEmpty()) {
                throw new IllegalStateException("Model returned an empty answer");
            }

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < output.size(); i++) {
                JsonObject item = output.get(i).getAsJsonObject();
                String type = item.has("type") ? item.get("type").getAsString() : "";
                String content = item.has("content") ? item.get("content").getAsString() : "";

                if ("message".equals(type) && !content.isEmpty()) {
                    result.append(content);
                    onLine.accept(content);
                }
            }

            if (result.toString().isBlank()) {
                throw new IllegalStateException("Model returned no message content");
            }
            return result.toString();
        }, ModelProvider.STREAM_EXECUTOR);
    }

    private URI uri(String path) {
        String base = config.baseUrl.endsWith("/") ? config.baseUrl.substring(0, config.baseUrl.length() - 1) : config.baseUrl;
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return URI.create(base + path);
    }
}
