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

    // Ollama streams newline-delimited JSON objects, each with a message.content fragment.
    @Override
    public CompletableFuture<String> stream(List<ChatMessage> messages, Consumer<String> onLine) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("stream", true);
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", 3000);
        options.addProperty("temperature", 0.2);
        body.add("options", options);
        JsonArray messagesJson = new JsonArray();
        for (ChatMessage m : messages) messagesJson.add(message(m.role(), m.content()));
        body.add("messages", messagesJson);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).thenApplyAsync(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error;
                try (Stream<String> lines = response.body()) { error = lines.collect(Collectors.joining("\n")); }
                throw new IllegalStateException("Ollama HTTP " + response.statusCode() + ": " + error);
            }
            LineSplitter splitter = new LineSplitter(onLine);
            String doneReason = null;
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isBlank()) continue;
                    JsonObject chunk = JsonParser.parseString(line).getAsJsonObject();
                    JsonObject message = chunk.getAsJsonObject("message");
                    if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
                        splitter.accept(message.get("content").getAsString());
                    }
                    if (chunk.has("done_reason") && !chunk.get("done_reason").isJsonNull()) doneReason = chunk.get("done_reason").getAsString();
                }
            }
            splitter.flush();
            if ("length".equals(doneReason)) throw PlanJson.truncated();
            if (splitter.text().isBlank()) throw new IllegalStateException("Model returned an empty answer");
            return splitter.text();
        }, ModelProvider.STREAM_EXECUTOR);
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
