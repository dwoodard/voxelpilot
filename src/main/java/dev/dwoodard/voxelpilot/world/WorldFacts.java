package dev.dwoodard.voxelpilot.world;

import dev.dwoodard.voxelpilot.build.Frame;
import dev.dwoodard.voxelpilot.selection.StructureSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Measurements the mod can make exactly and the model can't: distances, directions, what
// lies along the way, what's around. Handed over as short sentences in the plan's local
// terms, so the model copies numbers instead of doing geometry. Client thread.
public final class WorldFacts {
    private static final int AROUND = 24;
    private static final int SKY_CHECK = 20;

    private enum Kind { WATER, LAVA, TREES, BUILT }

    private WorldFacts() {}

    public static String describe(Minecraft mc, Frame frame) {
        if (mc.player == null || mc.level == null) return "";
        List<String> facts = new ArrayList<>();
        int[] p = frame.toLocal(mc.player.blockPosition());
        BlockState under = mc.level.getBlockState(mc.player.blockPosition().below());
        facts.add("Player stands at " + xyz(p) + " on " + id(under) + ".");

        // The point the request is about: the looked-at block, or the selection's floor center.
        int[] focus;
        if (frame.hasSelection()) {
            focus = new int[]{frame.width() / 2, 0, frame.depth() / 2};
            facts.add(relationToSelection(p, frame));
        } else {
            focus = new int[]{0, 0, 0};
            facts.add("Target 0 0 0 (" + id(mc.level.getBlockState(frame.origin())) + ") is " + offset(-p[0], -p[1], -p[2])
                + " from the player (straight-line distance " + Math.round(Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])) + ").");
        }

        String path = alongTheWay(mc, frame, p, focus);
        if (path != null) facts.add(path);
        facts.addAll(surroundings(mc, frame, focus));
        facts.add(openAbove(mc, frame, focus));
        return String.join("\n", facts);
    }

    private static String relationToSelection(int[] p, Frame f) {
        List<String> parts = new ArrayList<>();
        if (p[2] < 0) parts.add((-p[2]) + " behind its near edge");
        else if (p[2] >= f.depth()) parts.add((p[2] - f.depth() + 1) + " past its far edge");
        if (p[0] < 0) parts.add((-p[0]) + " to its left");
        else if (p[0] >= f.width()) parts.add((p[0] - f.width() + 1) + " to its right");
        String vertical = p[1] == 0 ? "level with its floor" : p[1] > 0 ? p[1] + " above its floor" : (-p[1]) + " below its floor";
        if (parts.isEmpty()) return "Player is inside the selection, " + vertical + ".";
        return "Player is " + String.join(", ", parts) + ", " + vertical + ".";
    }

    // Walk the straight line from the player to the focus and report the ground under it.
    private static String alongTheWay(Minecraft mc, Frame frame, int[] from, int[] to) {
        int dx = to[0] - from[0], dz = to[2] - from[2];
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps < 3) return null;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE, water = 0, lava = 0, firstWet = -1, lastWet = -1;
        for (int i = 1; i <= steps; i++) {
            int x = from[0] + Math.round((float) dx * i / steps), z = from[2] + Math.round((float) dz * i / steps);
            Column c = column(mc, frame, x, z);
            low = Math.min(low, c.height);
            high = Math.max(high, c.height);
            boolean wet = c.kind == Kind.WATER || c.kind == Kind.LAVA;
            if (c.kind == Kind.WATER) water++;
            if (c.kind == Kind.LAVA) lava++;
            if (wet) { if (firstWet < 0) firstWet = i; lastWet = i; }
        }
        StringBuilder s = new StringBuilder("Along the way (" + steps + " blocks): ground from y " + high + " down to y " + low);
        if (water > 0) s.append("; water under ").append(water).append(" of them (from ").append(firstWet).append(" to ").append(lastWet).append(" blocks out)");
        if (lava > 0) s.append("; LAVA under ").append(lava);
        return s.append('.').toString();
    }

    // Top-down read of the area: nearest of each kind with an exact offset, plus coverage.
    private static List<String> surroundings(Minecraft mc, Frame frame, int[] focus) {
        Map<Kind, int[]> nearest = new LinkedHashMap<>();
        Map<Kind, Integer> nearestDist = new LinkedHashMap<>();
        Map<Kind, long[]> sums = new LinkedHashMap<>();
        int total = 0;
        for (int dz = -AROUND; dz <= AROUND; dz += 2) {
            for (int dx = -AROUND; dx <= AROUND; dx += 2) {
                int x = focus[0] + dx, z = focus[2] + dz;
                Column c = column(mc, frame, x, z);
                total++;
                if (c.kind == null) continue;
                long[] sum = sums.computeIfAbsent(c.kind, k -> new long[3]);
                sum[0]++; sum[1] += dx; sum[2] += dz;
                int dist = Math.abs(dx) + Math.abs(dz);
                if (!nearestDist.containsKey(c.kind) || dist < nearestDist.get(c.kind)) {
                    nearestDist.put(c.kind, dist);
                    nearest.put(c.kind, new int[]{x, c.height, z});
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (!sums.containsKey(kind)) continue;
            long[] sum = sums.get(kind);
            int percent = (int) Math.round(100.0 * sum[0] / total);
            int[] n = nearest.get(kind);
            String where = percent >= 5 ? ", mostly " + direction(sum[1] / (double) sum[0], sum[2] / (double) sum[0]) : "";
            out.add(name(kind) + " within " + AROUND + ": " + Math.max(1, percent) + "% of the area" + where
                + "; nearest at " + xyz(n) + " (" + offset(n[0] - focus[0], n[1] - focus[1], n[2] - focus[2]) + " from " + (frame.hasSelection() ? "the selection center" : "the target") + ").");
        }
        if (out.isEmpty()) out.add("Within " + AROUND + ": only natural ground, no water, lava, trees, or builds.");
        return out;
    }

    private static String openAbove(Minecraft mc, Frame frame, int[] focus) {
        int clear = 0;
        for (int y = focus[1] + 1; y <= focus[1] + SKY_CHECK; y++) {
            if (!mc.level.getBlockState(frame.toWorld(focus[0], y, focus[2])).isAir()) break;
            clear++;
        }
        return "Open space above " + (frame.hasSelection() ? "the selection floor center" : "the target") + ": "
            + (clear >= SKY_CHECK ? SKY_CHECK + "+ blocks" : clear + " blocks") + ".";
    }

    private record Column(int height, Kind kind) {}

    private static Column column(Minecraft mc, Frame frame, int x, int z) {
        BlockPos base = frame.toWorld(x, 0, z);
        int top = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, base.getX(), base.getZ()) - 1;
        BlockPos pos = new BlockPos(base.getX(), top, base.getZ());
        BlockState state = mc.level.getBlockState(pos);
        return new Column(top - frame.origin().getY(), classify(state));
    }

    private static Kind classify(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) return Kind.WATER;
        if (state.getFluidState().is(FluidTags.LAVA)) return Kind.LAVA;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return Kind.TREES;
        if (!state.isAir() && !StructureSelector.isNatural(state)) return Kind.BUILT;
        return null;
    }

    private static String name(Kind kind) {
        return switch (kind) { case WATER -> "Water"; case LAVA -> "LAVA"; case TREES -> "Trees"; case BUILT -> "Built blocks"; };
    }

    // "33 forward, 5 right, 16 down"; directions in the plan's local terms.
    static String offset(int dx, int dy, int dz) {
        List<String> parts = new ArrayList<>();
        if (dz != 0) parts.add(Math.abs(dz) + (dz > 0 ? " forward" : " back"));
        if (dx != 0) parts.add(Math.abs(dx) + (dx > 0 ? " right" : " left"));
        if (dy != 0) parts.add(Math.abs(dy) + (dy > 0 ? " up" : " down"));
        return parts.isEmpty() ? "the same spot" : String.join(", ", parts);
    }

    private static String direction(double dx, double dz) {
        String fb = dz > 3 ? "forward" : dz < -3 ? "back" : "";
        String lr = dx > 3 ? "right" : dx < -3 ? "left" : "";
        if (fb.isEmpty() && lr.isEmpty()) return "all around";
        return fb.isEmpty() ? lr : lr.isEmpty() ? fb : fb + "-" + lr;
    }

    private static String xyz(int[] v) { return v[0] + " " + v[1] + " " + v[2]; }

    private static String id(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }
}
