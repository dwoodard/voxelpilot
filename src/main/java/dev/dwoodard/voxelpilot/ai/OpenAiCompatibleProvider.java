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

public final class OpenAiCompatibleProvider implements ModelProvider {
    private static final Gson GSON = new Gson();
    // Shared/static: a per-instance HttpClient can be garbage-collected mid-request
    // (ProviderFactory builds a fresh provider per call), which tears down the
    // channel and surfaces as ClosedChannelException. A static client stays reachable.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final long THINKING_LIMIT_MS = 30_000;
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Provider returned HTTP " + response.statusCode() + ": " + response.body());
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

    // Server-sent events: each "data:" line carries a content delta. Reasoning deltas are
    // ignored (they're the model thinking, not the script).
    @Override
    public CompletableFuture<String> stream(List<ChatMessage> messages, Consumer<String> onLine) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("temperature", 0.2);
        body.addProperty("stream", true);
        // Scripts are a few hundred tokens; the cap mostly bounds a runaway reasoning model.
        body.addProperty("max_tokens", 3000);
        // Thinking time dominates latency on local reasoning models (gpt-oss). Servers that
        // don't know the field ignore it.
        body.addProperty("reasoning_effort", "low");
        JsonArray messagesJson = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject object = new JsonObject();
            object.addProperty("role", m.role());
            object.addProperty("content", m.content());
            m.toolCalls().ifPresent(calls -> object.add("tool_calls", calls));
            m.toolCallId().ifPresent(id -> object.addProperty("tool_call_id", id));
            messagesJson.add(object);
        }
        body.add("messages", messagesJson);

        JsonArray tools = new JsonArray();
        for (ToolDefinition tool : ToolDefinition.all()) {
            JsonObject toolDef = new JsonObject();
            toolDef.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parameters());
            toolDef.add("function", function);
            tools.add(toolDef);
        }
        body.add("tools", tools);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        auth(builder);

        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines()).thenApplyAsync(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error;
                try (Stream<String> lines = response.body()) { error = lines.collect(Collectors.joining("\n")); }
                throw new IllegalStateException("Provider returned HTTP " + response.statusCode() + ": " + error);
            }
            LineSplitter splitter = new LineSplitter(onLine);
            boolean sawReasoning = false;
            String finish = null;
            JsonArray toolCalls = new JsonArray();
            long started = System.currentTimeMillis();
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    JsonArray choices = JsonParser.parseString(data).getAsJsonObject().getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta != null) {
                        splitter.accept(text(delta, "content"));
                        sawReasoning |= !text(delta, "reasoning_content").isEmpty() || !text(delta, "reasoning").isEmpty();
                        JsonArray calls = delta.getAsJsonArray("tool_calls");
                        if (calls != null && calls.size() > 0) {
                            for (JsonElement callElement : calls) {
                                JsonObject call = callElement.getAsJsonObject();
                                // Each chunk names its slot with "index"; arguments arrive as
                                // partial strings across many chunks and must be concatenated,
                                // not overwritten, or the accumulated JSON ends up truncated.
                                int index = call.has("index") ? call.get("index").getAsInt() : 0;
                                while (toolCalls.size() <= index) toolCalls.add(new JsonObject());
                                JsonObject existing = toolCalls.get(index).getAsJsonObject();
                                if (call.has("id")) existing.addProperty("id", call.get("id").getAsString());
                                if (call.has("type")) existing.addProperty("type", call.get("type").getAsString());
                                if (call.has("function")) {
                                    JsonObject func = call.getAsJsonObject("function");
                                    JsonObject existingFunc = existing.has("function")
                                        ? existing.getAsJsonObject("function") : new JsonObject();
                                    if (func.has("name")) existingFunc.addProperty("name", func.get("name").getAsString());
                                    if (func.has("arguments")) {
                                        String prev = existingFunc.has("arguments") ? existingFunc.get("arguments").getAsString() : "";
                                        existingFunc.addProperty("arguments", prev + func.get("arguments").getAsString());
                                    }
                                    existing.add("function", existingFunc);
                                }
                            }
                        }
                    }
                    if (sawReasoning && splitter.text().isBlank() && System.currentTimeMillis() - started > THINKING_LIMIT_MS) {
                        throw new IllegalStateException("Model spent " + THINKING_LIMIT_MS / 1000
                            + "s thinking without answering. Pick a faster model in Settings (Cmd+,), e.g. openai/gpt-oss-20b");
                    }
                    String reason = text(choice, "finish_reason");
                    if (!reason.isEmpty()) finish = reason;
                }
            }
            splitter.flush();
            if ("length".equals(finish)) throw PlanJson.truncated();

            if ("tool_calls".equals(finish) && toolCalls.size() > 0) {
                JsonObject result = new JsonObject();
                result.add("tool_calls", toolCalls);
                return GSON.toJson(result);
            }

            if (splitter.text().isBlank()) {
                throw new IllegalStateException(sawReasoning
                    ? "Model only produced reasoning and no answer; try a non-thinking model"
                    : "Model returned an empty answer");
            }
            return splitter.text();
        }, ModelProvider.STREAM_EXECUTOR);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private URI uri(String path) {
        String base = config.baseUrl.endsWith("/") ? config.baseUrl.substring(0, config.baseUrl.length() - 1) : config.baseUrl;
        return URI.create(base + path);
    }

    private void auth(HttpRequest.Builder builder) {
        if (config.apiKey != null && !config.apiKey.isBlank()) builder.header("Authorization", "Bearer " + config.apiKey);
    }
}
