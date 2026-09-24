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

public final class OllamaProvider implements ModelProvider {
    private static final Gson GSON = new Gson();
    private final ProviderConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public OllamaProvider(ProviderConfig config) { this.config = config; }
    @Override public String id() { return "ollama"; }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return listModels().thenApply(models -> true).exceptionally(e -> false);
    }

    @Override
    public CompletableFuture<List<String>> listModels() {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/tags")).GET().timeout(Duration.ofSeconds(10)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            JsonArray data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("models");
            List<String> models = new ArrayList<>();
            if (data != null) for (JsonElement element : data) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("name")) models.add(obj.get("name").getAsString());
            }
            return models;
        });
    }

    @Override
    public CompletableFuture<BuildPlan> plan(String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = root.getAsJsonObject("message").get("content").getAsString().trim();
            if (content.startsWith("```")) {
                int firstNewline = content.indexOf('\n');
                int lastFence = content.lastIndexOf("```");
                if (firstNewline >= 0 && lastFence > firstNewline) content = content.substring(firstNewline + 1, lastFence).trim();
            }
            BuildPlan plan = GSON.fromJson(content, BuildPlan.class);
            if (plan == null || plan.changes == null) throw new IllegalArgumentException("Model returned no build plan");
            return plan;
        });
    }

    private JsonObject message(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }

    private URI uri(String path) {
        String base = config.baseUrl.endsWith("/") ? config.baseUrl.substring(0, config.baseUrl.length() - 1) : config.baseUrl;
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return URI.create(base + path);
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Ollama HTTP " + response.statusCode() + ": " + response.body());
    }
}
