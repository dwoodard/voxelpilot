package dev.dwoodard.voxelpilot.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dwoodard.voxelpilot.config.ConfigStore;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldContextService {
    private static final Gson GSON = new Gson();
    private static final int MAX_SAMPLED_BLOCKS = 4096;

    private WorldContextService() {}

    public static String capture(Minecraft mc) {
        JsonObject root = new JsonObject();
        if (mc.player == null || mc.level == null) return "{}";

        JsonObject player = new JsonObject();
        player.addProperty("x", mc.player.getX());
        player.addProperty("y", mc.player.getY());
        player.addProperty("z", mc.player.getZ());
        player.addProperty("facing", mc.player.getDirection().getName());
        player.addProperty("creative", mc.player.getAbilities().instabuild);
        root.add("player", player);

        SelectionManager.get().box().ifPresent(box -> {
            JsonObject selection = new JsonObject();
            selection.add("min", pos(box.min()));
            selection.add("max", pos(box.max()));
            selection.addProperty("width", box.width());
            selection.addProperty("height", box.height());
            selection.addProperty("depth", box.depth());
            selection.addProperty("volume", box.volume());
            root.add("selection", selection);

            Map<String, Integer> palette = new LinkedHashMap<>();
            JsonArray samples = new JsonArray();
            long volume = box.volume();
            int stride = Math.max(1, (int)Math.ceil(Math.cbrt(Math.max(1.0, volume / (double) MAX_SAMPLED_BLOCKS))));
            int count = 0;
            for (int y = box.min().getY(); y <= box.max().getY() && count < MAX_SAMPLED_BLOCKS; y += stride) {
                for (int z = box.min().getZ(); z <= box.max().getZ() && count < MAX_SAMPLED_BLOCKS; z += stride) {
                    for (int x = box.min().getX(); x <= box.max().getX() && count < MAX_SAMPLED_BLOCKS; x += stride) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState state = mc.level.getBlockState(p);
                        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        String name = id == null ? "minecraft:air" : id.toString();
                        palette.merge(name, 1, Integer::sum);
                        if (!state.isAir()) {
                            JsonObject sample = new JsonObject();
                            sample.addProperty("x", x - box.min().getX());
                            sample.addProperty("y", y - box.min().getY());
                            sample.addProperty("z", z - box.min().getZ());
                            sample.addProperty("block", name);
                            samples.add(sample);
                        }
                        count++;
                    }
                }
            }
            JsonObject paletteJson = new JsonObject();
            palette.forEach(paletteJson::addProperty);
            root.add("samplePalette", paletteJson);
            root.add("sampleBlocks", samples);
            root.addProperty("sampleStride", stride);
        });

        root.addProperty("defaultContextRadius", ConfigStore.get().defaultContextRadius);
        root.addProperty("maxContextRadius", ConfigStore.get().maxContextRadius);
        return GSON.toJson(root);
    }

    private static JsonArray pos(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }
}
