package dev.dwoodard.voxelpilot.ai;

import com.google.gson.JsonObject;

public record ToolCall(
    String id,
    String functionName,
    JsonObject arguments
) {
    public String execute(ToolExecutor executor) {
        return executor.execute(functionName, arguments);
    }
}
