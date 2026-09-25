package dev.dwoodard.voxelpilot.selection;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

// "select this": grow from the block under the crosshair through connected built blocks
// (including diagonals) and stop at natural terrain, then box the result. Deterministic and
// instant; the AI is only needed when the thing is described rather than pointed at.
public final class StructureSelector {
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_REACH = 64;

    private StructureSelector() {}

    public static Optional<String> selectLookedAt(Minecraft mc) {
        if (mc.player == null || mc.level == null) return Optional.empty();
        Optional<BlockPos> target = SelectionManager.get().crosshairTarget(mc);
        if (target.isEmpty()) return Optional.of("Nothing under the crosshair");
        BlockPos start = target.get();
        Level level = mc.level;

        if (isNatural(level.getBlockState(start))) {
            SelectionManager.get().set(mc, start, start, mc.player.getDirection());
            return Optional.of("That looks like terrain; selected just that block");
        }

        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.asLong());
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        boolean capped = false;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos next = pos.offset(dx, dy, dz);
                if (next.distManhattan(start) > MAX_REACH * 3 || !seen.add(next.asLong())) continue;
                if (!level.hasChunkAt(next) || isNatural(level.getBlockState(next))) continue;
                if (seen.size() > MAX_BLOCKS) { capped = true; continue; }
                queue.add(next);
            }
        }
        SelectionManager.get().set(mc, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), mc.player.getDirection());
        return Optional.of("Selected " + SelectionManager.get().dimensions() + (capped ? " (stopped at the size limit)" : ""));
    }

    // "select the dirt" while pointing at dirt: every block of that same kind touching it
    // (face-connected, so a dirt layer doesn't leak diagonally into the next one), boxed.
    // Returns empty when the named word isn't the block under the crosshair, so the request
    // can go to the AI instead.
    public static Optional<String> selectConnectedSame(Minecraft mc, String name) {
        if (mc.player == null || mc.level == null) return Optional.empty();
        Optional<BlockPos> target = SelectionManager.get().crosshairTarget(mc);
        if (target.isEmpty()) return Optional.empty();
        BlockPos start = target.get();
        Level level = mc.level;
        Block block = level.getBlockState(start).getBlock();
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (name != null && !matches(path, name)) return Optional.empty();

        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.asLong());
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        int count = 0;
        boolean capped = false;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            count++;
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (Math.abs(next.getX() - start.getX()) > MAX_REACH || Math.abs(next.getY() - start.getY()) > MAX_REACH
                    || Math.abs(next.getZ() - start.getZ()) > MAX_REACH || !seen.add(next.asLong())) continue;
                if (!level.hasChunkAt(next) || !level.getBlockState(next).is(block)) continue;
                if (seen.size() > MAX_BLOCKS) { capped = true; continue; }
                queue.add(next);
            }
        }
        SelectionManager.get().set(mc, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), mc.player.getDirection());
        return Optional.of("Selected " + count + " connected " + path + " · box " + SelectionManager.get().dimensions()
            + (capped ? " (stopped at the size limit)" : ""));
    }

    // "dirt" ~ dirt, "grass" ~ grass_block, "oak planks" ~ oak_planks, "stones" ~ stone.
    private static boolean matches(String path, String name) {
        String n = name.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (n.endsWith("s") && !path.endsWith("s")) n = n.substring(0, n.length() - 1);
        return !n.isEmpty() && (path.equals(n) || path.startsWith(n + "_") || path.endsWith("_" + n) || path.contains(n));
    }

    // Terrain and plants: what a structure sits in, not what it's made of.
    static boolean isNatural(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) return true;
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
            || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.LEAVES)
            || state.is(Tags.Blocks.ORES) || state.is(Tags.Blocks.GRAVEL) || state.is(Tags.Blocks.SANDSTONE)
            || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(BlockTags.TERRACOTTA)
            || state.is(Blocks.BEDROCK) || state.is(Blocks.CLAY) || state.is(Blocks.END_STONE);
    }
}
