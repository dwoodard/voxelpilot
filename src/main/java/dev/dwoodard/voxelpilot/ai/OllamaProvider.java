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
    // Shared/static: a per-instance HttpClient can be garbage-collected mid-request
    // (ProviderFactory builds a fresh provider per call), which tears down the
    // channel and surfaces as ClosedChannelException. A static client stays reachable.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ProviderConfig config;

    public OllamaProvider(ProviderConfig config) { this.config = config; }
    @Override public String id() { return "ollama"; }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return listModels().thenApply(models -> true).exceptionally(e -> false);
    }

    @Override
    public CompletableFuture<List<String>> listModels() {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/tags")).GET().timeout(Duration.ofSeconds(10)).build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
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
    public CompletableFuture<BuildPlan> plan(List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("stream", false);
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", 8000);
        body.add("options", options);
        JsonArray messagesJson = new JsonArray();
        for (ChatMessage m : messages) messagesJson.add(message(m.role(), m.content()));
        body.add("messages", messagesJson);
        // Ollama 0.5+ accepts a JSON schema here for constrained decoding.
        body.add("format", PlanJson.schema());
        HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement doneReason = root.get("done_reason");
            if (doneReason != null && !doneReason.isJsonNull() && "length".equals(doneReason.getAsString())) throw PlanJson.truncated();
            return PlanJson.parse(root.getAsJsonObject("message").get("content").getAsString());
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
