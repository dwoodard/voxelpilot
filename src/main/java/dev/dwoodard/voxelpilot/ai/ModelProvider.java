package dev.dwoodard.voxelpilot.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public interface ModelProvider {
    // Reads streamed responses; blocking line iteration must stay off the HTTP client's
    // own threads and off the game threads.
    ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "VoxelPilot-stream");
        thread.setDaemon(true);
        return thread;
    });

    String id();
    CompletableFuture<Boolean> healthCheck();
    CompletableFuture<List<String>> listModels();

    // Streams the model's answer, calling onLine for each complete line as it arrives.
    // Completes with the full text.
    CompletableFuture<String> stream(List<ChatMessage> messages, Consumer<String> onLine);
}
