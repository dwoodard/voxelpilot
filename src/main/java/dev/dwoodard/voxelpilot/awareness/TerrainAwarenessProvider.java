package dev.dwoodard.voxelpilot.awareness;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TerrainAwarenessProvider {
    private static final int HORIZONTAL_RADIUS = 8;
    private static final int VERTICAL_RADIUS = 6;

    public List<Observation> observe(Minecraft mc, BlockPos target, BlockPos entry) {
        if (mc.level == null || mc.player == null) return List.of();

        BlockPos player = mc.player.blockPosition();
        Observation nearestLava = null;
        Observation nearestWater = null;

        for (BlockPos pos : BlockPos.betweenClosed(
            player.offset(-HORIZONTAL_RADIUS, -VERTICAL_RADIUS, -HORIZONTAL_RADIUS),
            player.offset(HORIZONTAL_RADIUS, VERTICAL_RADIUS, HORIZONTAL_RADIUS))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            var fluid = mc.level.getBlockState(pos).getFluidState();
            if (fluid.is(FluidTags.LAVA)) {
                nearestLava = nearer(nearestLava, observation(Type.LAVA, pos, Severity.CAUTION, player, entry, "LAVA"));
            } else if (fluid.is(FluidTags.WATER)) {
                nearestWater = nearer(nearestWater, observation(Type.WATER, pos, Severity.ADVISORY, player, entry, "WATER"));
            }
        }

        List<Observation> observations = new ArrayList<>();
        if (nearestLava != null) observations.add(nearestLava);
        if (nearestWater != null) observations.add(nearestWater);

        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-4, -3, -4), target.offset(4, 3, 4))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            if (mc.level.getBlockState(pos).isAir()
                && mc.level.getBlockState(pos.above()).isAir()
                && !mc.level.getBlockState(pos.below()).getCollisionShape(mc.level, pos.below()).isEmpty()) {
                observations.add(observation(Type.OPEN_SPACE, pos, Severity.INFO, player, entry, "OPEN SPACE"));
                break;
            }
        }

        return observations.stream()
            .sorted(Comparator.comparingDouble(Observation::relevance).reversed()
                .thenComparingInt(o -> o.severity().priority()))
            .toList();
    }

    private static Observation observation(Type type, BlockPos pos, Severity severity,
                                           BlockPos player, BlockPos objective, String message) {
        double distance = Math.sqrt(player.distSqr(pos));
        double objectiveDistance = Math.sqrt(objective.distSqr(pos));
        double relevance = severity.weight() * 100.0 + Math.max(0, 30.0 - objectiveDistance) - distance;
        return new Observation(type, pos.immutable(), severity, distance, relevance, 1.0, "terrain", message);
    }

    private static Observation nearer(Observation current, Observation candidate) {
        return current == null || candidate.distance() < current.distance() ? candidate : current;
    }

    public enum Type { LAVA, WATER, OPEN_SPACE, DROP }
    public enum Severity {
        CAUTION(0, 3), ADVISORY(1, 2), INFO(2, 1);
        private final int priority;
        private final int weight;
        Severity(int priority, int weight) { this.priority = priority; this.weight = weight; }
        public int priority() { return priority; }
        public int weight() { return weight; }
    }

    public record Observation(
        Type type,
        BlockPos position,
        Severity severity,
        double distance,
        double relevance,
        double confidence,
        String source,
        String message
    ) {}
}
