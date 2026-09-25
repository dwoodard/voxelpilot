package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.plan.Dir;
import dev.dwoodard.voxelpilot.selection.SelectionBox;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Optional;

// The plan's local coordinate frame: x = right, y = up, z = forward, relative to the way
// the player faced when anchoring. With a selection the origin is its left-near-bottom
// corner, so the whole selection is x 0..width-1, y 0..height-1, z 0..depth-1. Without one,
// the origin is the block under the crosshair. A frame is fixed per operation, so turning
// around between revisions never rotates the preview.
public record Frame(BlockPos origin, Direction forward, int width, int height, int depth) {
    public boolean hasSelection() { return width > 0; }

    public Direction right() { return forward.getClockWise(); }

    public BlockPos toWorld(int x, int y, int z) {
        Direction r = right();
        return origin.offset(r.getStepX() * x + forward.getStepX() * z, y, r.getStepZ() * x + forward.getStepZ() * z);
    }

    public int[] toLocal(BlockPos pos) {
        int dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ();
        Direction r = right();
        return new int[]{dx * r.getStepX() + dz * r.getStepZ(), pos.getY() - origin.getY(), dx * forward.getStepX() + dz * forward.getStepZ()};
    }

    public Direction toWorld(Dir dir) {
        return switch (dir) {
            case FORWARD -> forward;
            case BACK -> forward.getOpposite();
            case RIGHT -> right();
            case LEFT -> right().getOpposite();
        };
    }

    public static Frame fromSelection(SelectionBox box, Direction facing) {
        Direction forward = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        Direction right = forward.getClockWise();
        int x = pick(right.getStepX(), forward.getStepX(), box.min().getX(), box.max().getX());
        int z = pick(right.getStepZ(), forward.getStepZ(), box.min().getZ(), box.max().getZ());
        boolean rightIsX = right.getAxis() == Direction.Axis.X;
        return new Frame(new BlockPos(x, box.min().getY(), z), forward,
            rightIsX ? box.width() : box.depth(), box.height(), rightIsX ? box.depth() : box.width());
    }

    // Left/near edge on each horizontal axis: whichever of right/forward runs along it
    // decides whether local 0 sits at the min or the max world coordinate.
    private static int pick(int rightStep, int forwardStep, int min, int max) {
        int step = rightStep != 0 ? rightStep : forwardStep;
        return step > 0 ? min : max;
    }

    public static Optional<Frame> current(Minecraft mc) {
        SelectionManager selection = SelectionManager.get();
        Optional<SelectionBox> box = selection.box();
        if (box.isPresent()) return Optional.of(fromSelection(box.get(), selection.facingAtAnchor()));
        if (mc.player == null) return Optional.empty();
        Direction facing = mc.player.getDirection();
        return selection.crosshairTarget(mc).map(target -> new Frame(target, facing, 0, 0, 0));
    }
}
