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
    // Shared/static: a per-instance HttpClient can be garbage-collected mid-request
    // (ProviderFactory builds a fresh provider per call), which tears down the
    // channel and surfaces as ClosedChannelException. A static client stays reachable.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ProviderConfig config;

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
        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
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
    public CompletableFuture<BuildPlan> plan(List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("temperature", 0.2);
        // Without an explicit cap some servers default to a small max_tokens. Component
        // plans are small, so this is headroom for reasoning models, not for block lists.
        body.addProperty("max_tokens", 8000);
        JsonArray messagesJson = new JsonArray();
        for (ChatMessage m : messages) messagesJson.add(message(m.role(), m.content()));
        body.add("messages", messagesJson);
        // Grammar-constrained structured output (LM Studio / llama.cpp and most hosted
        // OpenAI-compatible APIs). Also sidesteps "Harmony"-style channel wrappers.
        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "voxelpilot_plan");
        jsonSchema.add("schema", PlanJson.schema());
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        body.add("response_format", responseFormat);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        auth(builder);

        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonElement finish = choice.get("finish_reason");
            if (finish != null && !finish.isJsonNull() && "length".equals(finish.getAsString())) throw PlanJson.truncated();
            JsonObject message = choice.getAsJsonObject("message");
            String content = text(message, "content");
            // With json_schema set, LM Studio returns some reasoning models' (e.g. Qwen 3.5)
            // constrained output in reasoning_content and leaves content empty.
            if (content.isBlank()) content = text(message, "reasoning_content");
            if (content.isBlank()) content = text(message, "reasoning");
            return PlanJson.parse(content);
        });
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
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
