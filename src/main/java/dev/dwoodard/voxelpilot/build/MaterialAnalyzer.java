package dev.dwoodard.voxelpilot.build;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.LinkedHashMap;
import java.util.Map;

// Survival accounting. Every placed state costs its block item once; the second half of a
// door, bed, or tall plant is free because the item places both halves in vanilla too.
public final class MaterialAnalyzer {
    private MaterialAnalyzer() {}

    // null = costs nothing (air, or the second half of a two-block item).
    // Items.AIR = has no item, so it cannot be placed legitimately in survival.
    public static Item itemFor(BlockState state) {
        if (state.isAir()) return null;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return null;
        if (state.hasProperty(BlockStateProperties.BED_PART)
            && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return null;
        return state.getBlock().asItem();
    }

    public static Map<Item, Integer> required(Iterable<ResolvedChange> changes) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (var change : changes) {
            Item item = itemFor(change.state());
            if (item != null) out.merge(item, 1, Integer::sum);
        }
        return out;
    }

    public static int countInventory(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        return count;
    }

    public static boolean consumeOne(ServerPlayer player, BlockState state) {
        if (player.getAbilities().instabuild) return true;
        Item item = itemFor(state);
        if (item == null) return true;
        if (item == Items.AIR) return false;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return false;
    }
}
