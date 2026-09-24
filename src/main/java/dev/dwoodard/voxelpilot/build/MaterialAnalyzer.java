package dev.dwoodard.voxelpilot.build;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MaterialAnalyzer {
    private MaterialAnalyzer() {}

    public static Map<String, Integer> required(Iterable<GhostPreviewManager.ResolvedChange> changes) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var change : changes) {
            if ("minecraft:air".equals(change.blockId())) continue;
            out.merge(change.blockId(), 1, Integer::sum);
        }
        return out;
    }

    public static int countInventory(ServerPlayer player, String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) return 0;
        Block block = BuiltInRegistries.BLOCK.get(id);
        Item item = block.asItem();
        if (item == null) return 0;
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) count += stack.getCount();
        return count;
    }

    public static boolean consumeOne(ServerPlayer player, String blockId) {
        if (player.getAbilities().instabuild || "minecraft:air".equals(blockId)) return true;
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) return false;
        Item item = BuiltInRegistries.BLOCK.get(id).asItem();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item) && !stack.isEmpty()) {
                stack.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return false;
    }
}
