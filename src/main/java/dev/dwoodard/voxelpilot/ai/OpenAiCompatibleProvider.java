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

public final class OpenAiCompatibleProvider implements ModelProvider {
    private static final Gson GSON = new Gson();
    private final ProviderConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public OpenAiCompatibleProvider(ProviderConfig config) { this.config = config; }

    @Override public String id() { return "openai-compatible"; }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return listModels().thenApply(models -> true).exceptionally(e -> false);
    }

    @Override
    public CompletableFuture<List<String>> listModels() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/models")).GET().timeout(Duration.ofSeconds(10));
        auth(builder);
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
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
    public CompletableFuture<BuildPlan> plan(String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("temperature", 0.2);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        auth(builder);

        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
            return parsePlan(content);
        });
    }

    private BuildPlan parsePlan(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) json = json.substring(firstNewline + 1, lastFence).trim();
        }
        BuildPlan plan = GSON.fromJson(json, BuildPlan.class);
        if (plan == null || plan.changes == null) throw new IllegalArgumentException("Model returned no build plan");
        return plan;
    }

    private JsonObject message(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }

    private URI uri(String path) {
        String base = config.baseUrl.endsWith("/") ? config.baseUrl.substring(0, config.baseUrl.length() - 1) : config.baseUrl;
        return URI.create(base + path);
    }

    private void auth(HttpRequest.Builder builder) {
        if (config.apiKey != null && !config.apiKey.isBlank()) builder.header("Authorization", "Bearer " + config.apiKey);
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Provider returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }
}
