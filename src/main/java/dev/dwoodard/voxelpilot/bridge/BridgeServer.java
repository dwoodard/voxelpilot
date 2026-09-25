package dev.dwoodard.voxelpilot.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dwoodard.voxelpilot.VoxelPilot;
import dev.dwoodard.voxelpilot.ai.ProviderFactory;
import dev.dwoodard.voxelpilot.ai.ChatMessage;
import dev.dwoodard.voxelpilot.world.BlockCatalog;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
            server.createContext("/blocks", this::blocks);
            server.createContext("/state", this::state);
            server.createContext("/screenshot", this::screenshot);
            server.createContext("/map", this::map);
            server.createContext("/inspect", this::inspect);
            server.createContext("/plan", this::plan);
            server.createContext("/facts", this::facts);
            server.createContext("/cancel", this::cancel);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            VoxelPilot.LOGGER.info("VoxelPilot bridge listening on 127.0.0.1:8765");
        } catch (IOException e) {
            // Port taken (e.g. a second game instance): run without the bridge.
            server = null;
            VoxelPilot.LOGGER.error("VoxelPilot bridge could not start on 127.0.0.1:8765", e);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized boolean isRunning() { return server != null; }

    public CompletableFuture<String> stream(List<ChatMessage> messages, Consumer<String> onLine) {
        ensureRunning();
        return ProviderFactory.current().stream(messages, onLine);
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

    // GET /blocks          -> every registered block with its states
    // GET /blocks?q=piston -> best matches, same as what a planning request would include
    private void blocks(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String q = null;
        if (query != null) for (String pair : query.split("&")) {
            if (pair.startsWith("q=")) q = java.net.URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8);
        }
        var entries = q == null || q.isBlank() ? BlockCatalog.all() : BlockCatalog.search(q, 50);
        send(exchange, 200, GSON.toJson(entries.stream().map(BlockCatalog.Entry::description).toList()));
    }

    // GET /state -> player, frame, selection, preview (with script), build status
    private void state(HttpExchange exchange) throws IOException {
        handle(exchange, () -> send(exchange, 200, GSON.toJson(onClient(Perception::state))));
    }

    // GET /screenshot -> PNG of exactly what the player sees (ghost included)
    private void screenshot(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            requireWorld();
            sendPng(exchange, onClient(mc -> {
                try { return Perception.screenshot(mc); } catch (IOException e) { throw new IllegalStateException(e); }
            }));
        });
    }

    // GET /map?radius=24 -> PNG top-down map in plan coordinates (forward = up), with
    // selection, ghost, player, and a labeled grid
    private void map(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            requireWorld();
            int radius = Math.max(4, Math.min(96, intParam(exchange, "radius", 24)));
            Perception.MapData data = onClient(mc -> Perception.sampleMap(mc, radius));
            sendPng(exchange, Perception.drawMap(data));
        });
    }

    // GET /inspect?x=0&y=0&z=0&w=8&h=4&d=8 -> exact blocks in a local box as text
    private void inspect(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            requireWorld();
            int x = intParam(exchange, "x", 0), y = intParam(exchange, "y", 0), z = intParam(exchange, "z", 0);
            int w = intParam(exchange, "w", 8), h = intParam(exchange, "h", 4), d = intParam(exchange, "d", 8);
            var pinned = frameParam(exchange);
            sendText(exchange, onClient(mc -> Perception.inspect(mc, pinned.orElseGet(() -> Perception.frame(mc)), x, y, z, w, h, d)));
        });
    }

    // POST /plan  body = script lines (text) -> ghost preview in the current frame (the one
    // /state reports). Returns what was accepted and why anything was rejected. It never
    // builds: the player confirms in-game.
    private void plan(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            if (!"POST".equals(exchange.getRequestMethod())) throw new IllegalStateException("POST a script as the request body");
            requireWorld();
            String script = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject result = onClient(mc -> {
                var outcome = dev.dwoodard.voxelpilot.ai.AiPlanner.applyScript(mc, script, frameParam(exchange).orElseGet(() -> Perception.frame(mc)));
                JsonObject r = new JsonObject();
                r.addProperty("title", outcome.plan().title);
                r.addProperty("message", outcome.plan().message);
                r.addProperty("acceptedLines", outcome.plan().script.size());
                com.google.gson.JsonArray skipped = new com.google.gson.JsonArray();
                outcome.skipped().forEach(skipped::add);
                r.add("skipped", skipped);
                if (outcome.resolved() != null) {
                    r.addProperty("revision", dev.dwoodard.voxelpilot.build.GhostPreviewManager.get().revision());
                    r.addProperty("changes", outcome.resolved().changes().size());
                    r.addProperty("replacesExisting", outcome.resolved().replacedExisting());
                    r.addProperty("outsideSelection", outcome.resolved().outsideSelection());
                    com.google.gson.JsonArray notes = new com.google.gson.JsonArray();
                    outcome.resolved().notes().forEach(notes::add);
                    r.add("notes", notes);
                }
                return r;
            });
            send(exchange, 200, GSON.toJson(result));
        });
    }

    // GET /facts -> the exact FACTS block a planning request would include right now
    private void facts(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            requireWorld();
            var pinned = frameParam(exchange);
            sendText(exchange, onClient(mc -> dev.dwoodard.voxelpilot.world.WorldFacts.describe(mc, pinned.orElseGet(() -> Perception.frame(mc)))));
        });
    }

    // POST /cancel -> clear the ghost preview (nothing is built)
    private void cancel(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            onClient(mc -> { dev.dwoodard.voxelpilot.build.GhostPreviewManager.get().clear(); return true; });
            send(exchange, 200, "{\"cancelled\":true}");
        });
    }

    private interface Handler { void run() throws Exception; }

    private static void handle(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.run();
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            send(exchange, 503, "{\"error\":\"" + escape(root.getMessage()) + "\"}");
        }
    }

    // World reads must happen on the game thread; never block an HTTP caller forever on it.
    private static <T> T onClient(java.util.function.Function<Minecraft, T> read) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return mc.submit(() -> read.apply(mc)).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void requireWorld() throws Exception {
        if (!onClient(mc -> mc.player != null && mc.level != null)) throw new IllegalStateException("Not in a world");
    }

    // ?origin=x,y,z&forward=west pins the frame to world coordinates, so an agent's script
    // or inspection doesn't drift when the player selects or previews something else.
    private static java.util.Optional<dev.dwoodard.voxelpilot.build.Frame> frameParam(HttpExchange exchange) {
        String origin = stringParam(exchange, "origin"), forward = stringParam(exchange, "forward");
        if (origin == null) return java.util.Optional.empty();
        String[] p = origin.split(",");
        if (p.length != 3) throw new IllegalArgumentException("origin must be x,y,z");
        var dir = forward == null ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.byName(forward.toLowerCase());
        if (dir == null || !dir.getAxis().isHorizontal()) throw new IllegalArgumentException("forward must be north, south, east, or west");
        return java.util.Optional.of(new dev.dwoodard.voxelpilot.build.Frame(new net.minecraft.core.BlockPos(
            Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())), dir, 0, 0, 0));
    }

    private static String stringParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) return java.net.URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static int intParam(HttpExchange exchange, String name, int fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                try { return Integer.parseInt(pair.substring(name.length() + 1)); } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    private static void sendPng(HttpExchange exchange, byte[] png) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, png.length);
        exchange.getResponseBody().write(png);
        exchange.close();
    }

    private static void sendText(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
