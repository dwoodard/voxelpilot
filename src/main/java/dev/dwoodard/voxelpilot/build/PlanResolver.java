package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.ai.BuildPlan;
import dev.dwoodard.voxelpilot.plan.LocalChange;
import dev.dwoodard.voxelpilot.plan.PlanException;
import dev.dwoodard.voxelpilot.plan.PlanRenderer;
import dev.dwoodard.voxelpilot.selection.SelectionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Plan -> exact world changes. Runs on the client thread against the client's copy of the
// world (read-only). Anything invalid throws PlanException; a plan is either fully valid or
// rejected, never partially applied.
public final class PlanResolver {
    public static final int MAX_WORLD_CHANGES = 100_000;

    private PlanResolver() {}

    public static ResolvedPlan resolve(Level level, BuildPlan plan, Frame frame, Optional<SelectionBox> selection) {
        List<LocalChange> local = PlanRenderer.render(plan.nodes).changes();
        BlockSpecResolver specs = new BlockSpecResolver(frame);
        List<ResolvedChange> changes = new ArrayList<>(local.size());
        int replaced = 0, outside = 0, unchanged = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (LocalChange c : local) {
            BlockPos pos = frame.toWorld(c.x(), c.y(), c.z());
            if (level.isOutsideBuildHeight(pos)) throw new PlanException("Block at local " + c.x() + " " + c.y() + " " + c.z() + " is outside world height");
            if (!level.hasChunkAt(pos)) throw new PlanException("Part of the plan is in an unloaded area; move closer or build smaller");
            BlockState target = specs.resolve(c.block());
            BlockState existing = level.getBlockState(pos);
            // Already exactly this block: nothing to preview, place, or pay for.
            if (existing == target) { unchanged++; continue; }
            if (!existing.isAir()) replaced++;
            if (selection.isPresent() && !contains(selection.get(), pos)) outside++;
            changes.add(new ResolvedChange(pos, target));
            if (changes.size() > MAX_WORLD_CHANGES) {
                throw new PlanException(String.format("This would change more than %,d blocks; select a smaller area or do it in parts", MAX_WORLD_CHANGES));
            }
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }

        List<String> notes = new ArrayList<>(specs.notes());
        if (unchanged > 0) notes.add(unchanged + " blocks already match and are skipped");
        if (outside > 0) notes.add(outside + " changes are outside the selection");
        BlockPos min = changes.isEmpty() ? frame.origin() : new BlockPos(minX, minY, minZ);
        BlockPos max = changes.isEmpty() ? frame.origin() : new BlockPos(maxX, maxY, maxZ);
        return new ResolvedPlan(plan, frame, List.copyOf(changes), replaced, outside, min, max, List.copyOf(notes));
    }

    private static boolean contains(SelectionBox box, BlockPos pos) {
        return pos.getX() >= box.min().getX() && pos.getX() <= box.max().getX()
            && pos.getY() >= box.min().getY() && pos.getY() <= box.max().getY()
            && pos.getZ() >= box.min().getZ() && pos.getZ() <= box.max().getZ();
    }
}
