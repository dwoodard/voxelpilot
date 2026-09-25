package dev.dwoodard.voxelpilot.selection;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;

public final class SelectionManager {
    private static final SelectionManager INSTANCE = new SelectionManager();
    private static final double PICK_DISTANCE = 100.0;

    private BlockPos anchor;
    private BlockPos min;
    private BlockPos max;
    private Direction facingAtAnchor = Direction.NORTH;

    private SelectionManager() {}

    public static SelectionManager get() { return INSTANCE; }

    // Extended-reach crosshair raycast, shared by J-selection and by the no-selection
    // AI planning fallback so "build X here" has somewhere to anchor to even without J.
    public Optional<BlockPos> crosshairTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null) return Optional.empty();
        HitResult hit = mc.player.pick(PICK_DISTANCE, 0.0F, false);
        return hit instanceof BlockHitResult blockHit ? Optional.of(blockHit.getBlockPos().immutable()) : Optional.empty();
    }

    public void selectCrosshair(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        Optional<BlockPos> target = crosshairTarget(mc);
        if (target.isEmpty()) {
            message(mc, "No block under crosshair within 100 blocks");
            return;
        }

        BlockPos pos = target.get();
        if (anchor == null) {
            anchor = pos.immutable();
            min = anchor;
            max = anchor;
            facingAtAnchor = mc.player.getDirection();
            message(mc, "Anchor A: " + format(anchor));
        } else {
            min = min(anchor, pos);
            max = max(anchor, pos);
            message(mc, "Point B: " + format(pos) + " · " + dimensions());
        }
    }

    // Programmatic selection (by request, not J): corners inclusive, arrow keys then work
    // relative to the given facing just as after pressing J.
    public void set(Minecraft mc, BlockPos a, BlockPos b, Direction facing) {
        anchor = a.immutable();
        min = min(a, b);
        max = max(a, b);
        facingAtAnchor = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        message(mc, "Selected " + dimensions());
    }

    public void clear(Minecraft mc) {
        anchor = null;
        min = null;
        max = null;
        message(mc, "Selection cleared");
    }

    public void nudgeHorizontal(Direction relativeFace, boolean inward) {
        if (anchor == null) return;
        Direction world = relativeToWorld(relativeFace);
        moveFace(world, inward ? -1 : 1);
    }

    public void nudgeVertical(boolean topFace, boolean inward) {
        if (anchor == null) return;
        Direction face = topFace ? Direction.UP : Direction.DOWN;
        moveFace(face, inward ? -1 : 1);
    }

    private void moveFace(Direction face, int amount) {
        int minX = min.getX(), minY = min.getY(), minZ = min.getZ();
        int maxX = max.getX(), maxY = max.getY(), maxZ = max.getZ();

        switch (face) {
            case EAST -> maxX = Math.max(minX, maxX + amount);
            case WEST -> minX = Math.min(maxX, minX - amount);
            case UP -> maxY = Math.max(minY, maxY + amount);
            case DOWN -> minY = Math.min(maxY, minY - amount);
            case SOUTH -> maxZ = Math.max(minZ, maxZ + amount);
            case NORTH -> minZ = Math.min(maxZ, minZ - amount);
        }

        min = new BlockPos(minX, minY, minZ);
        max = new BlockPos(maxX, maxY, maxZ);
    }

    private Direction relativeToWorld(Direction relative) {
        Direction forward = facingAtAnchor.getAxis().isHorizontal() ? facingAtAnchor : Direction.NORTH;
        Direction right = forward.getClockWise();
        return switch (relative) {
            case NORTH -> forward;
            case SOUTH -> forward.getOpposite();
            case EAST -> right;
            case WEST -> right.getOpposite();
            default -> relative;
        };
    }

    public Optional<SelectionBox> box() {
        return min == null || max == null ? Optional.empty() : Optional.of(new SelectionBox(min, max));
    }

    public Optional<BlockPos> anchor() { return Optional.ofNullable(anchor); }
    public Direction facingAtAnchor() { return facingAtAnchor; }

    public String dimensions() {
        return box().map(b -> b.width() + "×" + b.height() + "×" + b.depth()).orElse("none");
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    private static String format(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    private static void message(Minecraft mc, String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] " + text), true);
    }
}
