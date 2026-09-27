package dev.dwoodard.voxelpilot.ai;

import com.google.gson.JsonObject;
import java.util.List;

public record ToolDefinition(
    String name,
    String description,
    JsonObject parameters
) {
    public static List<ToolDefinition> all() {
        return List.of(
            new ToolDefinition(
                "query_blocks",
                "Get detailed block data in a region (x1,y1,z1 to x2,y2,z2 inclusive)",
                parametersObject("x1", "y1", "z1", "x2", "y2", "z2")
            ),
            new ToolDefinition(
                "find_nearest_block",
                "Locate the nearest instance of a block type from player position",
                parametersObject("block_type", "max_distance")
            ),
            new ToolDefinition(
                "analyze_terrain",
                "Get terrain topology: ground levels, water, slopes in a region",
                parametersObject("x1", "z1", "x2", "z2", "resolution")
            ),
            new ToolDefinition(
                "get_player_state",
                "Get current player position, inventory, gamemode, facing direction",
                emptyParameters()
            ),
            new ToolDefinition(
                "check_inventory_available",
                "Check if player has or can gather N items",
                parametersObject("item_type", "count")
            ),
            new ToolDefinition(
                "measure_distance",
                "Calculate distance and direction between two points",
                parametersObject("x1", "y1", "z1", "x2", "y2", "z2")
            ),
            new ToolDefinition(
                "check_line_of_sight",
                "Check if there's a clear line of sight between two points",
                parametersObject("x1", "y1", "z1", "x2", "y2", "z2")
            ),
            new ToolDefinition(
                "list_references",
                "Get nearby reference points (player-designated locations)",
                parametersObject("type", "max_distance")
            ),
            new ToolDefinition(
                "check_constraints",
                "Check what rules apply to a region (protected, water, caves, etc)",
                parametersObject("x1", "y1", "z1", "x2", "y2", "z2")
            )
        );
    }

    private static JsonObject parametersObject(String... fields) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        for (String field : fields) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", inferType(field));
            prop.addProperty("description", field);
            properties.add(field, prop);
        }
        params.add("properties", properties);
        params.add("required", com.google.gson.JsonParser.parseString(
            "[" + String.join(",", "\"" + String.join("\",\"", fields) + "\"") + "]"
        ).getAsJsonArray());
        return params;
    }

    private static JsonObject emptyParameters() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", new JsonObject());
        params.add("required", new com.google.gson.JsonArray());
        return params;
    }

    private static String inferType(String field) {
        if (field.contains("type") || field.equals("biome") || field.contains("direction")) return "string";
        if (field.contains("distance") || field.contains("time")) return "number";
        if (field.contains("count") || field.startsWith("x") || field.startsWith("y") || field.startsWith("z")
            || field.contains("resolution")) return "integer";
        return "string";
    }
}
