package dev.dwoodard.voxelpilot.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ModelProvider {
    String id();
    CompletableFuture<Boolean> healthCheck();
    CompletableFuture<List<String>> listModels();
    CompletableFuture<BuildPlan> plan(String systemPrompt, String userPrompt);
}
