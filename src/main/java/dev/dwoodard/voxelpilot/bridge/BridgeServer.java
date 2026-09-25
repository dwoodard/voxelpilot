package dev.dwoodard.voxelpilot.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dwoodard.voxelpilot.VoxelPilot;
import dev.dwoodard.voxelpilot.ai.ProviderFactory;
import dev.dwoodard.voxelpilot.ai.BuildPlan;
import dev.dwoodard.voxelpilot.ai.ChatMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

public final class BridgeServer {
    private static final Gson GSON = new Gson();
    private static final BridgeServer INSTANCE = new BridgeServer();
    private HttpServer server;

    private BridgeServer() {}
    public static BridgeServer get() { return INSTANCE; }

    public synchronized void ensureRunning() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
            server.createContext("/health", this::health);
            server.createContext("/models", this::models);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            VoxelPilot.LOGGER.info("VoxelPilot bridge listening on 127.0.0.1:8765");
        } catch (IOException e) {
            throw new IllegalStateException("Could not start VoxelPilot bridge", e);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized boolean isRunning() { return server != null; }

    public CompletableFuture<BuildPlan> plan(List<ChatMessage> messages) {
        ensureRunning();
        return ProviderFactory.current().plan(messages);
    }

    private void health(HttpExchange exchange) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("status", "ready");
        body.addProperty("provider", ProviderFactory.current().id());
        send(exchange, 200, GSON.toJson(body));
    }

    private void models(HttpExchange exchange) throws IOException {
        try {
            var models = ProviderFactory.current().listModels().join();
            send(exchange, 200, GSON.toJson(models));
        } catch (Exception e) {
            send(exchange, 503, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String escape(String text) {
        if (text == null) return "unknown";
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
