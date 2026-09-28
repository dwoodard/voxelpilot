package dev.dwoodard.voxelpilot.bridge;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dwoodard.voxelpilot.VoxelPilot;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class VoxelPilotMcpServer {
    private static final Gson GSON = new Gson();
    private static final VoxelPilotMcpServer INSTANCE = new VoxelPilotMcpServer();
    private static final AtomicLong MESSAGE_ID = new AtomicLong(1);
    private HttpServer server;

    private VoxelPilotMcpServer() {}
    public static VoxelPilotMcpServer get() { return INSTANCE; }

    public synchronized void ensureRunning() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8766), 0);
            server.createContext("/", this::handleRequest);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            VoxelPilot.LOGGER.info("VoxelPilot MCP server listening on 127.0.0.1:8766");
        } catch (IOException e) {
            server = null;
            VoxelPilot.LOGGER.error("VoxelPilot MCP server could not start on 127.0.0.1:8766", e);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0);
            exchange.close();
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        JsonObject response = handleMcpRequest(request);

        byte[] responseBytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private JsonObject handleMcpRequest(JsonObject request) {
        String method = request.has("method") ? request.get("method").getAsString() : null;
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        long id = request.has("id") ? request.get("id").getAsLong() : MESSAGE_ID.incrementAndGet();

        JsonObject result = switch (method) {
            case "initialize" -> handleInitialize();
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolCall(params);
            case "resources/list" -> handleResourcesList();
            case "resources/read" -> handleResourcesRead(params);
            case "prompts/list" -> handlePromptsList();
            case "prompts/get" -> handlePromptsGet(params);
            default -> error("Unknown method: " + method);
        };

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("result", result);
        response.addProperty("id", id);
        return response;
    }

    private JsonObject handleInitialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        result.addProperty("serverName", "VoxelPilot");
        result.addProperty("serverVersion", "1.0.0");
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        capabilities.add("resources", new JsonObject());
        capabilities.add("prompts", new JsonObject());
        result.add("capabilities", capabilities);
        return result;
    }

    private JsonObject handleToolsList() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("query_blocks",
            "Query blocks in a region (x1,y1,z1 to x2,y2,z2 inclusive)",
            new String[]{"x1", "y1", "z1", "x2", "y2", "z2"}));

        tools.add(toolDef("get_player_state",
            "Get current player position, inventory, gamemode",
            new String[]{}));

        tools.add(toolDef("wayfinder_search",
            "Search for a block or entity type",
            new String[]{"query"}));

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject handleToolCall(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : null;
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        String output = switch (name) {
            case "query_blocks" -> queryBlocks(arguments);
            case "get_player_state" -> getPlayerState();
            case "wayfinder_search" -> wayfinderSearch(arguments);
            default -> "{\"error\": \"Unknown tool: " + name + "\"}";
        };

        JsonObject result = new JsonObject();
        result.addProperty("content", output);
        result.addProperty("isError", false);
        return result;
    }

    private JsonObject handleResourcesList() {
        JsonArray resources = new JsonArray();

        JsonObject stateResource = new JsonObject();
        stateResource.addProperty("uri", "game://state");
        stateResource.addProperty("name", "Game State");
        stateResource.addProperty("description", "Current Minecraft game state");
        resources.add(stateResource);

        JsonObject result = new JsonObject();
        result.add("resources", resources);
        return result;
    }

    private JsonObject handleResourcesRead(JsonObject params) {
        String uri = params.has("uri") ? params.get("uri").getAsString() : null;

        String content = switch (uri) {
            case "game://state" -> getGameState();
            default -> "{\"error\": \"Unknown resource: " + uri + "\"}";
        };

        JsonObject result = new JsonObject();
        result.addProperty("contents", content);
        result.addProperty("mimeType", "application/json");
        return result;
    }

    private JsonObject handlePromptsList() {
        JsonArray prompts = new JsonArray();

        JsonObject gameContextPrompt = new JsonObject();
        gameContextPrompt.addProperty("name", "game-context");
        gameContextPrompt.addProperty("description", "Current game context and state");
        prompts.add(gameContextPrompt);

        JsonObject result = new JsonObject();
        result.add("prompts", prompts);
        return result;
    }

    private JsonObject handlePromptsGet(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : null;

        String prompt = switch (name) {
            case "game-context" -> getGameContextPrompt();
            default -> "Unknown prompt: " + name;
        };

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);

        JsonObject result = new JsonObject();
        result.add("messages", messages);
        return result;
    }

    private String queryBlocks(JsonObject args) {
        // TODO: Call actual block query via BridgeServer or direct game access
        return "{\"blocks\": [], \"total\": 0}";
    }

    private String getPlayerState() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "{\"error\": \"Player not available\"}";

        JsonObject state = new JsonObject();
        state.addProperty("x", mc.player.getX());
        state.addProperty("y", mc.player.getY());
        state.addProperty("z", mc.player.getZ());
        state.addProperty("gamemode", mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "unknown");
        return state.toString();
    }

    private String wayfinderSearch(JsonObject args) {
        // TODO: Implement wayfinder search
        return "{\"results\": []}";
    }

    private String getGameState() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject state = new JsonObject();
        state.addProperty("dimension", mc.level != null ? mc.level.dimension().location().toString() : "unknown");
        state.addProperty("time", mc.level != null ? mc.level.getDayTime() : 0);
        if (mc.player != null) {
            state.addProperty("player_x", mc.player.getX());
            state.addProperty("player_y", mc.player.getY());
            state.addProperty("player_z", mc.player.getZ());
        }
        return state.toString();
    }

    private String getGameContextPrompt() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an AI assistant helping with Minecraft tasks in VoxelPilot.\n");
        if (mc.player != null) {
            prompt.append("Player position: ").append(mc.player.getBlockX()).append(", ")
                .append(mc.player.getBlockY()).append(", ")
                .append(mc.player.getBlockZ()).append("\n");
        }
        if (mc.level != null) {
            prompt.append("Dimension: ").append(mc.level.dimension().location()).append("\n");
        }
        prompt.append("\nYou have access to tools to query blocks, search for items, and control wayfinding.");
        return prompt.toString();
    }

    private JsonObject toolDef(String name, String description, String[] params) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        for (String param : params) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            prop.addProperty("description", param);
            properties.add(param, prop);
        }
        inputSchema.add("properties", properties);

        JsonArray required = new JsonArray();
        for (String param : params) {
            required.add(param);
        }
        inputSchema.add("required", required);

        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private JsonObject error(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return error;
    }
}
