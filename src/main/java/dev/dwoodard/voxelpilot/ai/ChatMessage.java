package dev.dwoodard.voxelpilot.ai;

import com.google.gson.JsonArray;
import java.util.Optional;

public record ChatMessage(String role, String content, Optional<JsonArray> toolCalls, Optional<String> toolCallId) {
    public ChatMessage(String role, String content) {
        this(role, content, Optional.empty(), Optional.empty());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage assistantWithTools(JsonArray toolCalls) {
        return new ChatMessage("assistant", "", Optional.of(toolCalls), Optional.empty());
    }

    public static ChatMessage toolResult(String content, String toolCallId) {
        return new ChatMessage("tool", content, Optional.empty(), Optional.of(toolCallId));
    }
}
