package dev.dwoodard.voxelpilot.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;

import java.util.HashMap;
import java.util.Map;

// Applies one change the way a player would: break and place packets within reach. Nothing here
// touches the server directly, so the server stays authoritative (rules, protection, anti-cheat).
final class RemotePlacer {
    enum Outcome { DONE, SENT, BUSY, OUT_OF_REACH, NO_SUPPORT, MISSING_ITEM, FAILED }

    // Hotbar stacks replaced while creative-filling a slot, restored when the build ends.
    private final Map<Integer, ItemStack> savedHotbar = new HashMap<>();
    private Item missing;

    Item missing() { return missing; }

    Outcome step(Minecraft mc, ResolvedChange change) {
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        BlockPos pos = change.pos();
        BlockState want = change.state();
        BlockState have = mc.level.getBlockState(pos);

        if (want.isAir()) {
            if (have.isAir()) return Outcome.DONE;
            if (!inReach(player, Vec3.atCenterOf(pos))) return Outcome.OUT_OF_REACH;
            return destroy(mc, pos) ? Outcome.DONE : Outcome.BUSY;
        }
        if (have == want) return Outcome.DONE;

        Item item = MaterialAnalyzer.itemFor(want);
        if (item == null) return Outcome.DONE; // second half of a two-block item
        if (item == Items.AIR) return Outcome.FAILED;

        if (!have.isAir() && !have.canBeReplaced()) {
            if (!inReach(player, Vec3.atCenterOf(pos))) return Outcome.OUT_OF_REACH;
            destroy(mc, pos);
            return Outcome.BUSY;
        }

        BlockHitResult hit = findHit(mc, pos, have);
        if (hit == null) return Outcome.NO_SUPPORT;
        if (!inReach(player, hit.getLocation())) return Outcome.OUT_OF_REACH;
        if (!select(mc, item)) { missing = item; return Outcome.MISSING_ITEM; }

        InteractionResult result = gm.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) return Outcome.FAILED;
        player.swing(InteractionHand.MAIN_HAND);
        return Outcome.SENT;
    }

    // Breaking is instant in creative and takes ticks in survival; true once the block is gone.
    private boolean destroy(Minecraft mc, BlockPos pos) {
        MultiPlayerGameMode gm = mc.gameMode;
        if (mc.level.getBlockState(pos).isAir()) return true;
        if (!gm.isDestroying()) gm.startDestroyBlock(pos, Direction.UP);
        else gm.continueDestroyBlock(pos, Direction.UP);
        return mc.level.getBlockState(pos).isAir();
    }

    void stopDestroy(Minecraft mc) {
        if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
    }

    void restoreHotbar(Minecraft mc) {
        if (mc.gameMode != null && mc.player != null && mc.gameMode.hasInfiniteItems()) {
            savedHotbar.forEach((slot, stack) -> mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot));
        }
        savedHotbar.clear();
        missing = null;
    }

    private static boolean inReach(LocalPlayer player, Vec3 target) {
        double reach = player.getAttributeValue(ForgeMod.BLOCK_REACH.get()) - 0.5;
        return player.getEyePosition().distanceToSqr(target) <= reach * reach;
    }

    // Click the replaced block itself when it is replaceable (grass, water); otherwise click a
    // solid, non-interactive neighbour on the face that points into the target cell.
    private static BlockHitResult findHit(Minecraft mc, BlockPos pos, BlockState have) {
        if (!have.isAir()) return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            BlockState state = mc.level.getBlockState(neighbour);
            if (state.isAir() || state.canBeReplaced() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) continue;
            Direction face = dir.getOpposite();
            Vec3 location = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(location, face, neighbour, false);
        }
        return null;
    }

    // Survival only uses what is already in the hotbar. Creative may fill the selected slot.
    private boolean select(Minecraft mc, Item item) {
        Inventory inv = mc.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inv.items.get(slot).is(item)) { inv.selected = slot; return true; }
        }
        if (!mc.gameMode.hasInfiniteItems()) return false;
        int slot = inv.selected;
        savedHotbar.putIfAbsent(slot, inv.items.get(slot).copy());
        mc.gameMode.handleCreativeModeItemAdd(new ItemStack(item), 36 + slot);
        return true;
    }
}
