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

public final class LmStudioResponsesProvider implements ModelProvider {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ProviderConfig config;

    public LmStudioResponsesProvider(ProviderConfig config) { this.config = config; }

    @Override public String id() { return "lm-studio-responses"; }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return listModels().thenApply(models -> !models.isEmpty()).exceptionally(e -> false);
    }

    @Override
    public CompletableFuture<List<String>> listModels() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/models")).GET().timeout(Duration.ofSeconds(10));
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
        String userInput = "";
        for (ChatMessage m : messages) {
            if ("user".equals(m.role())) {
                userInput = m.content();
                break;
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("input", userInput);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", 3000);

        JsonArray tools = new JsonArray();
        JsonObject mcpTool = new JsonObject();
        mcpTool.addProperty("type", "mcp");
        mcpTool.addProperty("server_label", "voxelpilot");
        mcpTool.addProperty("server_url", "http://127.0.0.1:8766");
        tools.add(mcpTool);
        body.add("tools", tools);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/responses"))
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

                if ("message".equals(type)) {
                    JsonArray content = item.has("content") ? item.getAsJsonArray("content") : null;
                    if (content != null) {
                        for (JsonElement elem : content) {
                            JsonObject contentItem = elem.getAsJsonObject();
                            String contentType = contentItem.has("type") ? contentItem.get("type").getAsString() : "";
                            String text = contentItem.has("text") ? contentItem.get("text").getAsString() : "";

                            if ("output_text".equals(contentType) && !text.isEmpty()) {
                                result.append(text);
                                onLine.accept(text);
                            }
                        }
                    }
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
        return URI.create(base + "/v1" + path);
    }
}
