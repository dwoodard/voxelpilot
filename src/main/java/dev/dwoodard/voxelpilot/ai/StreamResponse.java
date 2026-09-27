package dev.dwoodard.voxelpilot.ai;

import java.util.List;

public record StreamResponse(
    String content,
    List<ToolCall> toolCalls
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public static StreamResponse content(String text) {
        return new StreamResponse(text, List.of());
    }

    public static StreamResponse toolCalls(List<ToolCall> calls) {
        return new StreamResponse("", calls);
    }

    public static StreamResponse both(String text, List<ToolCall> calls) {
        return new StreamResponse(text, calls);
    }
}
