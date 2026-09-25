package dev.dwoodard.voxelpilot.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.dwoodard.voxelpilot.build.Frame;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

// A compact, token-budgeted summary of the world in the plan's local frame. Models can't
// reason over thousands of raw block samples, but they can read a small heightmap
// ("where is the ground") and a per-layer composition ("what's underground").
public final class WorldContextService {
    private static final Gson GSON = new Gson();
    private static final int MAX_GRID = 16;        // surface grid is at most 16x16 cells
    private static final int MAX_LAYERS = 16;
    private static final int AROUND = 8;           // no-selection: +-8 blocks around the target

    private WorldContextService() {}

    public static String capture(Minecraft mc, Frame frame) {
        JsonObject root = new JsonObject();
        if (mc.player == null || mc.level == null) return "{}";

        root.addProperty("gameMode", mc.player.getAbilities().instabuild ? "creative" : "survival");
        if (frame.hasSelection()) {
            root.add("selection", ints(frame.width(), frame.height(), frame.depth()));
            root.add("surface", surface(mc, frame, 0, 0, frame.width(), frame.depth(), frame.height() - 1, 0));
            root.add("layers", layers(mc, frame));
        } else {
            BlockState target = mc.level.getBlockState(frame.origin());
            root.addProperty("target", id(target));
            root.add("surface", surface(mc, frame, -AROUND, -AROUND, AROUND * 2 + 1, AROUND * 2 + 1, AROUND, -AROUND));
        }

        // A selection is the whole answer to "where": the model gets no crosshair or player
        // position to anchor to instead. Only whether the player stands inside it (safety).
        int[] p = frame.toLocal(mc.player.blockPosition());
        if (frame.hasSelection()) {
            boolean inside = p[0] >= 0 && p[0] < frame.width() && p[1] >= 0 && p[1] < frame.height() && p[2] >= 0 && p[2] < frame.depth();
            if (inside) root.addProperty("playerInsideSelection", true);
        } else {
            root.add("player", ints(p[0], p[1], p[2]));
        }

        if (!mc.player.getAbilities().instabuild) root.add("inventory", inventory(mc));
        return GSON.toJson(root);
    }

    // Height of the topmost solid block per column, in local y, scanning down from yTop to
    // yBottom. Rows run near -> far (z), columns left -> right (x). null = nothing solid.
    private static JsonObject surface(Minecraft mc, Frame frame, int x0, int z0, int w, int d, int yTop, int yBottom) {
        int stride = Math.max(1, (int) Math.ceil(Math.max(w, d) / (double) MAX_GRID));
        Map<String, Integer> blocks = new LinkedHashMap<>();
        JsonArray rows = new JsonArray();
        Integer flat = null;
        boolean isFlat = true;
        for (int z = z0; z < z0 + d; z += stride) {
            JsonArray row = new JsonArray();
            for (int x = x0; x < x0 + w; x += stride) {
                Integer top = null;
                for (int y = yTop; y >= yBottom; y--) {
                    BlockState state = mc.level.getBlockState(frame.toWorld(x, y, z));
                    if (isSolidSurface(state)) {
                        top = y;
                        blocks.merge(id(state), 1, Integer::sum);
                        break;
                    }
                }
                if (top == null) row.add(JsonNull.INSTANCE); else row.add(top);
                if (top == null || (flat != null && !flat.equals(top))) isFlat = false;
                if (flat == null) flat = top;
            }
            rows.add(row);
        }
        JsonObject out = new JsonObject();
        // Most builds sit on level ground: one number instead of a grid.
        if (isFlat && flat != null) {
            out.addProperty("flat", flat);
        } else {
            out.add("from", ints(x0, z0));
            out.addProperty("stride", stride);
            out.add("heights", rows);
        }
        out.add("blocks", top(blocks, 4));
        return out;
    }

    // What each y-level of the selection is mostly made of (top 3 blocks per sampled layer).
    private static JsonArray layers(Minecraft mc, Frame frame) {
        int yStride = Math.max(1, (int) Math.ceil(frame.height() / (double) MAX_LAYERS));
        int xzStride = Math.max(1, (int) Math.ceil(Math.max(frame.width(), frame.depth()) / (double) MAX_GRID));
        JsonArray out = new JsonArray();
        JsonObject run = null;
        int runTop = 0;
        for (int y = frame.height() - 1; y >= 0; y -= yStride) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int z = 0; z < frame.depth(); z += xzStride) {
                for (int x = 0; x < frame.width(); x += xzStride) {
                    counts.merge(id(mc.level.getBlockState(frame.toWorld(x, y, z))), 1, Integer::sum);
                }
            }
            JsonObject blocks = top(counts, 3);
            // Runs of identical layers (all air above ground, all stone below) collapse to "y":"3-15".
            if (run != null && run.get("blocks").equals(blocks)) {
                run.addProperty("y", y + "-" + runTop);
                continue;
            }
            run = new JsonObject();
            runTop = y;
            run.addProperty("y", String.valueOf(y));
            run.add("blocks", blocks);
            out.add(run);
        }
        return out;
    }

    private static JsonObject inventory(Minecraft mc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : mc.player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
            }
        }
        return top(counts, 20);
    }

    private static boolean isSolidSurface(BlockState state) {
        if (state.isAir()) return false;
        // Grass tufts and flowers aren't the ground; water is.
        return !(state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    // Namespace dropped to save tokens; the plan side accepts bare ids.
    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static JsonObject top(Map<String, Integer> counts, int limit) {
        JsonObject out = new JsonObject();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .forEach(e -> out.addProperty(e.getKey(), e.getValue()));
        return out;
    }

    private static JsonArray ints(int... values) {
        JsonArray array = new JsonArray();
        for (int v : values) array.add(v);
        return array;
    }
}
