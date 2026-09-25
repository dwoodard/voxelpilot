package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.plan.PlanException;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

// Moves or turns the current preview without asking the model again. A preview is a
// script in a local frame, so moving shifts the frame and rotating turns it; every block
// state written in local terms (stair facing, hopper direction, comparator input) turns with
// it. The plan is re-resolved at the new spot, so replaced/unchanged/outside counts stay true,
// and the result is a new revision: confirm builds exactly what's shown. Client thread.
public final class PreviewMover {
    private static final int MAX_DROP = 64;

    private PreviewMover() {}

    // dx = right, dz = forward, relative to where the player is facing now.
    public static String move(Minecraft mc, int dx, int dy, int dz) {
        Optional<ResolvedPlan> current = GhostPreviewManager.get().plan();
        if (current.isEmpty() || GhostPreviewManager.get().drafting()) return "No preview to move";
        Direction forward = mc.player != null ? mc.player.getDirection() : Direction.NORTH;
        Direction right = forward.getClockWise();
        BlockPos offset = new BlockPos(right.getStepX() * dx + forward.getStepX() * dz, dy, right.getStepZ() * dx + forward.getStepZ() * dz);
        return shift(mc, current.get(), offset);
    }

    // Bottom-center of the preview onto the block above the crosshair target.
    public static String moveHere(Minecraft mc) {
        Optional<ResolvedPlan> current = GhostPreviewManager.get().plan();
        if (current.isEmpty() || GhostPreviewManager.get().drafting()) return "No preview to move";
        Optional<BlockPos> target = SelectionManager.get().crosshairTarget(mc);
        if (target.isEmpty()) return "Look at a block to move the preview there";
        ResolvedPlan plan = current.get();
        BlockPos bottomCenter = new BlockPos((plan.min().getX() + plan.max().getX()) / 2, plan.min().getY(), (plan.min().getZ() + plan.max().getZ()) / 2);
        return shift(mc, plan, target.get().above().subtract(bottomCenter));
    }

    // Lower until the preview rests on something (or raise it out of the ground).
    public static String drop(Minecraft mc) {
        Optional<ResolvedPlan> current = GhostPreviewManager.get().plan();
        if (current.isEmpty() || GhostPreviewManager.get().drafting() || mc.level == null) return "No preview to move";
        ResolvedPlan plan = current.get();
        int bottom = plan.min().getY();
        Set<Long> footprint = new HashSet<>();
        for (ResolvedChange c : plan.changes()) if (c.pos().getY() == bottom && !c.removal()) footprint.add(BlockPos.asLong(c.pos().getX(), 0, c.pos().getZ()));
        if (footprint.isEmpty()) return "Nothing to rest on the ground";

        int dy = 0;
        if (anySolid(mc, footprint, bottom)) {
            while (dy < MAX_DROP && anySolid(mc, footprint, bottom + dy)) dy++;
        } else {
            while (dy > -MAX_DROP && !anySolid(mc, footprint, bottom + dy - 1)) dy--;
        }
        if (dy == 0) return "Already on the ground";
        return shift(mc, plan, new BlockPos(0, dy, 0));
    }

    // Quarter turns around the preview's center: 1 = clockwise (right), -1 = left, 2 = around.
    public static String rotate(Minecraft mc, int quarterTurns) {
        Optional<ResolvedPlan> current = GhostPreviewManager.get().plan();
        if (current.isEmpty() || GhostPreviewManager.get().drafting()) return "No preview to rotate";
        ResolvedPlan plan = current.get();
        int turns = ((quarterTurns % 4) + 4) % 4;
        if (turns == 0) return "No change";
        Vec3 center = new Vec3((plan.min().getX() + plan.max().getX() + 1) / 2.0, 0, (plan.min().getZ() + plan.max().getZ() + 1) / 2.0);

        Frame old = plan.frame();
        Direction forward = old.forward();
        for (int i = 0; i < turns; i++) forward = forward.getClockWise();
        // Rotate the origin block's center about the preview center, then back to a block.
        Vec3 originCenter = new Vec3(old.origin().getX() + 0.5, 0, old.origin().getZ() + 0.5);
        Vec3 rotated = rotateY(originCenter.subtract(center), turns).add(center);
        BlockPos origin = new BlockPos((int) Math.floor(rotated.x), old.origin().getY(), (int) Math.floor(rotated.z));
        Frame frame = new Frame(origin, forward, old.width(), old.height(), old.depth());
        String result = apply(mc, plan, frame);
        if (result.startsWith("Can't")) return result;
        SelectionManager.get().box().ifPresent(box -> {
            if (old.hasSelection()) SelectionManager.get().set(mc, frame.toWorld(0, 0, 0),
                frame.toWorld(old.width() - 1, old.height() - 1, old.depth() - 1), frame.forward());
        });
        return "Rotated " + (turns == 1 ? "right" : turns == 3 ? "left" : "around") + " · " + result;
    }

    private static String shift(Minecraft mc, ResolvedPlan plan, BlockPos offset) {
        if (offset.equals(BlockPos.ZERO)) return "No change";
        Frame old = plan.frame();
        Frame frame = new Frame(old.origin().offset(offset), old.forward(), old.width(), old.height(), old.depth());
        String result = apply(mc, plan, frame);
        if (result.startsWith("Can't")) return result;
        // Keep a selection that belonged to this preview wrapped around it.
        SelectionManager.get().box().ifPresent(box -> {
            if (old.hasSelection()) SelectionManager.get().set(mc, box.min().offset(offset), box.max().offset(offset), frame.forward());
        });
        return "Moved " + describe(offset) + " · " + result;
    }

    private static String apply(Minecraft mc, ResolvedPlan plan, Frame frame) {
        if (mc.level == null) return "Can't move: no world";
        try {
            ResolvedPlan moved = PlanResolver.resolve(mc.level, plan.plan(), frame, Optional.empty());
            GhostPreviewManager.get().setPlan(moved);
            return "rev " + GhostPreviewManager.get().revision() + " · " + moved.changes().size() + " changes"
                + (moved.replacedExisting() > 0 ? " · replaces " + moved.replacedExisting() : "");
        } catch (PlanException e) {
            return "Can't move there: " + e.getMessage();
        }
    }

    private static boolean anySolid(Minecraft mc, Set<Long> footprint, int y) {
        for (long key : footprint) {
            BlockPos column = BlockPos.of(key);
            var state = mc.level.getBlockState(new BlockPos(column.getX(), y, column.getZ()));
            if (!state.isAir() && !state.canBeReplaced()) return true;
        }
        return false;
    }

    private static Vec3 rotateY(Vec3 v, int clockwiseTurns) {
        Vec3 r = v;
        // Clockwise seen from above: north (-z) -> east (+x): (x, z) -> (-z, x).
        for (int i = 0; i < clockwiseTurns; i++) r = new Vec3(-r.z, 0, r.x);
        return r;
    }

    private static String describe(BlockPos offset) {
        StringBuilder s = new StringBuilder();
        if (offset.getY() != 0) s.append(offset.getY() > 0 ? "up " : "down ").append(Math.abs(offset.getY())).append(' ');
        int horizontal = Math.abs(offset.getX()) + Math.abs(offset.getZ());
        if (horizontal > 0) s.append(horizontal).append(" across");
        return s.toString().trim();
    }
}
