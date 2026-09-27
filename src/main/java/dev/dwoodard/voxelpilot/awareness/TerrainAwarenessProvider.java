package dev.dwoodard.voxelpilot.awareness;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TerrainAwarenessProvider {
    private static final int HORIZONTAL_RADIUS = 8;
    private static final int VERTICAL_RADIUS = 6;

    public List<Observation> observe(Minecraft mc, BlockPos focus) {
        if (mc.level == null || mc.player == null) return List.of();

        List<Observation> observations = new ArrayList<>();
        BlockPos player = mc.player.blockPosition();

        // Keep V1 local and bounded. These are warnings/observations, not a route claim.
        for (BlockPos pos : BlockPos.betweenClosed(
            player.offset(-HORIZONTAL_RADIUS, -VERTICAL_RADIUS, -HORIZONTAL_RADIUS),
            player.offset(HORIZONTAL_RADIUS, VERTICAL_RADIUS, HORIZONTAL_RADIUS))) {

            var state = mc.level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                observations.add(new Observation(Type.LAVA, pos.immutable(), Severity.CAUTION,
                    distance(player, pos), "LAVA"));
            } else if (state.getFluidState().is(FluidTags.WATER)) {
                observations.add(new Observation(Type.WATER, pos.immutable(), Severity.ADVISORY,
                    distance(player, pos), "WATER"));
            }
        }

        // Clearance at the current standing position is immediately actionable.
        BlockPos feet = player;
        if (!mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()) {
            observations.add(new Observation(Type.CLEARANCE, feet.above().immutable(), Severity.CAUTION,
                1, "HEAD CLEARANCE"));
        }

        // A nearby air pocket around the target is useful evidence of cave access,
        // but is deliberately labeled as an observation rather than a safe route.
        for (BlockPos pos : BlockPos.betweenClosed(focus.offset(-4, -3, -4), focus.offset(4, 3, 4))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            if (mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos.above()).isAir()
                && !mc.level.getBlockState(pos.below()).getCollisionShape(mc.level, pos.below()).isEmpty()) {
                observations.add(new Observation(Type.CAVE_OPENING, pos.immutable(), Severity.INFO,
                    distance(player, pos), "CAVE OPENING"));
                break;
            }
        }

        return observations.stream()
            .sorted(Comparator.comparingInt((Observation o) -> o.severity().priority())
                .thenComparingDouble(Observation::distance))
            .limit(6)
            .toList();
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    public enum Type { LAVA, WATER, CLEARANCE, CAVE_OPENING, DROP }
    public enum Severity {
        CAUTION(0), ADVISORY(1), INFO(2);
        private final int priority;
        Severity(int priority) { this.priority = priority; }
        public int priority() { return priority; }
    }

    public record Observation(Type type, BlockPos position, Severity severity, double distance, String message) {}
}
