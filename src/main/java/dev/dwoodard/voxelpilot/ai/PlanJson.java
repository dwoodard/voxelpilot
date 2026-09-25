package dev.dwoodard.voxelpilot.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dwoodard.voxelpilot.VoxelPilot;

import java.util.ArrayList;

// The model-facing plan contract, shared by every provider: the JSON schema used for
// constrained decoding, and a tolerant parser for the response text.
public final class PlanJson {
    private static final Gson GSON = new Gson();

    private PlanJson() {}

    // Flat, mostly-optional node fields: grammar-constrained small models handle this far
    // better than a oneOf-per-component union. Semantic checks happen in PlanRenderer.
    public static JsonObject schema() {
        JsonObject node = object();
        JsonObject p = new JsonObject();
        p.add("id", type("string"));
        p.add("type", enumOf("box", "stairs", "roof", "door", "blocks"));
        p.add("on", type("string"));
        p.add("at", triple());
        p.add("size", triple());
        p.add("block", type("string"));
        p.add("fill", enumOf("solid", "hollow", "walls", "outline"));
        p.add("direction", enumOf("forward", "back", "left", "right"));
        p.add("length", type("integer"));
        p.add("width", type("integer"));
        p.add("vertical", enumOf("up", "down"));
        p.add("carve", type("boolean"));
        p.add("ridge", type("string"));
        p.add("ends", type("string"));
        JsonObject raw = object();
        JsonObject rawProps = new JsonObject();
        rawProps.add("at", triple());
        rawProps.add("block", type("string"));
        raw.add("properties", rawProps);
        raw.add("required", strings("at", "block"));
        p.add("blocks", arrayOf(raw));
        node.add("properties", p);
        // "block" and "length" are required even where they're defaulted or ignored: under
        // grammar-constrained decoding small models skip optional fields (block on walls,
        // length on stairs), and a forced field costs a few tokens.
        node.add("required", strings("id", "type", "block", "length"));

        JsonObject move = object();
        JsonObject moveProps = new JsonObject();
        moveProps.add("x", type("number"));
        moveProps.add("y", type("number"));
        moveProps.add("z", type("number"));
        moveProps.add("reason", type("string"));
        move.add("properties", moveProps);

        JsonObject root = object();
        JsonObject props = new JsonObject();
        props.add("title", type("string"));
        props.add("message", type("string"));
        props.add("nodes", arrayOf(node));
        props.add("suggestedMove", move);
        root.add("properties", props);
        root.add("required", strings("title", "message", "nodes"));
        return root;
    }

    public static BuildPlan parse(String content) {
        String json = content == null ? "" : content.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) json = json.substring(firstNewline + 1, lastFence).trim();
        }
        // Some local models (e.g. gpt-oss "Harmony" format) prefix the JSON with channel
        // markers like "<|channel|>final <|message|>{...}". Take the outermost {...}.
        int firstBrace = json.indexOf('{');
        int lastBrace = json.lastIndexOf('}');
        if (firstBrace > 0 && lastBrace > firstBrace) json = json.substring(firstBrace, lastBrace + 1);
        BuildPlan plan;
        try {
            plan = GSON.fromJson(json, BuildPlan.class);
        } catch (Exception e) {
            VoxelPilot.LOGGER.error("VoxelPilot: model did not return valid JSON. Raw content: {}", content);
            throw new IllegalArgumentException("Model did not return valid JSON (see log for raw response)", e);
        }
        if (plan == null) throw new IllegalArgumentException("Model returned no plan");
        if (plan.nodes == null) plan.nodes = new ArrayList<>();
        return plan;
    }

    public static String toJson(Object value) { return GSON.toJson(value); }

    public static IllegalStateException truncated() {
        return new IllegalStateException("Model output was cut off before the plan finished. Ask for something smaller, or raise the model's output limit.");
    }

    private static JsonObject object() { JsonObject o = new JsonObject(); o.addProperty("type", "object"); return o; }

    private static JsonObject type(String type) { JsonObject o = new JsonObject(); o.addProperty("type", type); return o; }

    private static JsonObject enumOf(String... values) { JsonObject o = type("string"); o.add("enum", strings(values)); return o; }

    private static JsonObject arrayOf(JsonObject items) { JsonObject o = type("array"); o.add("items", items); return o; }

    private static JsonObject triple() {
        JsonObject o = arrayOf(type("integer"));
        o.addProperty("minItems", 3);
        o.addProperty("maxItems", 3);
        return o;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }
}
