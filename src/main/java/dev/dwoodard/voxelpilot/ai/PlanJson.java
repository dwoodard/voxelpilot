package dev.dwoodard.voxelpilot.ai;

import com.google.gson.Gson;

public final class PlanJson {
    private static final Gson GSON = new Gson();

    private PlanJson() {}

    public static String toJson(Object value) { return GSON.toJson(value); }

    public static IllegalStateException truncated() {
        return new IllegalStateException("Model output was cut off before the plan finished. Ask for something smaller, or raise the model's output limit.");
    }
}
