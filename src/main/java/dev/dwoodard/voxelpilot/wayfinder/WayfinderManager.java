package dev.dwoodard.voxelpilot.wayfinder;

import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class WayfinderManager {
    private static final WayfinderManager INSTANCE = new WayfinderManager();
    private static final int SEARCH_CHUNK_RADIUS = 8;

    private Target active;
    private Set<BlockPos> previousSearchResults = Set.of();

    private WayfinderManager() {}

    public static WayfinderManager get() { return INSTANCE; }

    public List<Suggestion> suggestions(String query, int limit) {
        String needle = normalize(query);
        if (needle.isBlank()) return List.of();

        return ForgeRegistries.BLOCKS.getValues().stream()
            .map(block -> new Suggestion(block, displayName(block), ForgeRegistries.BLOCKS.getKey(block)))
            .filter(s -> {
                String name = normalize(s.name());
                String id = s.id() == null ? "" : normalize(s.id().toString());
                return name.contains(needle) || id.contains(needle);
            })
            .sorted(Comparator
                .comparingInt((Suggestion s) -> matchRank(normalize(s.name()), needle))
                .thenComparing(Suggestion::name))
            .limit(limit)
            .toList();
    }

    public Optional<Target> followReference(Minecraft mc, String token) {
        if (mc == null || mc.player == null || token == null) return Optional.empty();
        String name = token.startsWith("@") ? token.substring(1) : token;
        var reference = ReferenceResolver.resolve(mc, name);
        if (reference.isEmpty() || !reference.get().hasPosition()) return Optional.empty();
        var ref = reference.get();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(ref.dimension())) return Optional.empty();
        BlockPos pos = new BlockPos(ref.x(), ref.y(), ref.z());
        double distance = Math.sqrt(mc.player.blockPosition().distSqr(pos));
        Target target = new Target(TargetKind.REFERENCE, ref.token(), ref.name(), null,
            ref.dimension(), pos, pos, distance);
        previousSearchResults = Set.of();
        active = target;
        return Optional.of(target);
    }

    public Optional<Target> findNearest(Minecraft mc, String query) {
        previousSearchResults = Set.of();
        return findSearchTarget(mc, query, previousSearchResults);
    }

    public Optional<Target> findNext(Minecraft mc) {
        if (active == null || active.kind() != TargetKind.SEARCH || active.blockId() == null) return Optional.empty();
        Target previous = active;
        previousSearchResults = new java.util.HashSet<>(previousSearchResults);
        previousSearchResults.add(previous.pos());
        Optional<Target> next = findSearchTarget(mc, previous.query(), previousSearchResults);
        if (next.isEmpty()) active = previous;
        return next;
    }

    public boolean canFindNext() {
        return active != null && active.kind() == TargetKind.SEARCH;
    }

    private Optional<Target> findSearchTarget(Minecraft mc, String query, Set<BlockPos> excluded) {
        if (mc.player == null || mc.level == null) return Optional.empty();

        List<Suggestion> matches = suggestions(query, 12);
        if (matches.isEmpty()) return Optional.empty();

        Block wanted = matches.get(0).block();
        ResourceLocation wantedId = ForgeRegistries.BLOCKS.getKey(wanted);
        BlockPos player = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        int playerChunkX = player.getX() >> 4;
        int playerChunkZ = player.getZ() >> 4;

        for (int cx = playerChunkX - SEARCH_CHUNK_RADIUS; cx <= playerChunkX + SEARCH_CHUNK_RADIUS; cx++) {
            for (int cz = playerChunkZ - SEARCH_CHUNK_RADIUS; cz <= playerChunkZ + SEARCH_CHUNK_RADIUS; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();

                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.is(wanted))) continue;

                    int baseY = SectionPos.sectionToBlockCoord(mc.level.getSectionYFromSectionIndex(sectionIndex));
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!section.getBlockState(x, y, z).is(wanted)) continue;
                                BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                                if (excluded.contains(pos)) continue;
                                double distance = player.distSqr(pos);
                                if (distance < bestDistance) {
                                    bestDistance = distance;
                                    best = pos;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (best == null) return Optional.empty();
        BlockPos approach = approachPosition(mc, best);
        Target target = new Target(TargetKind.SEARCH, query, displayName(wanted), wantedId,
            mc.level.dimension().location().toString(), best.immutable(), approach, Math.sqrt(bestDistance));
        active = target;
        return Optional.of(target);
    }

    private static BlockPos approachPosition(Minecraft mc, BlockPos target) {
        // Underground targets use an offset suggested entry so Wayfinder never visually
        // suggests standing directly above the objective and digging straight down.
        int directSurfaceY = mc.level.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
            target.getX(), target.getZ());
        int depth = directSurfaceY - target.getY();
        boolean underground = depth > 4;

        int minRadius = underground ? 4 : 0;
        int maxRadius = underground ? 12 : 8;
        BlockPos player = mc.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int radius = minRadius; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;

                    int x = target.getX() + dx;
                    int z = target.getZ() + dz;
                    int y = mc.level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!canPlayerStandAt(mc, feet)) continue;

                    // Prefer entries convenient to the player while keeping underground
                    // targets deliberately offset from the target column.
                    double playerDistance = feet.distSqr(player);
                    double horizontalOffset = Math.sqrt(dx * dx + dz * dz);
                    // No magic "ideal" offset: reward a valid, non-zero offset while
                    // primarily preferring an entry convenient to the player.
                    double offsetPenalty = underground ? (1.0 / horizontalOffset) * 64.0 : 0.0;
                    double score = playerDistance + offsetPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        best = feet;
                    }
                }
            }
        }

        if (best != null) return best.immutable();

        // Fallback preserves designation without claiming that the fallback is a traversable
        // descent route.
        return new BlockPos(target.getX(), directSurfaceY, target.getZ());
    }

    private static boolean canPlayerStandAt(Minecraft mc, BlockPos feet) {
        BlockPos floor = feet.below();
        if (mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) return false;
        if (!mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()) return false;

        BlockPos head = feet.above();
        return mc.level.getBlockState(head).getCollisionShape(mc.level, head).isEmpty();
    }

    public Optional<Target> active() { return Optional.ofNullable(active); }

    public void clear() { active = null; previousSearchResults = Set.of(); }

    public String describe(Target target, BlockPos player) {
        int dy = target.pos().getY() - player.getY();
        String vertical = dy == 0 ? "same level" : Math.abs(dy) + " " + (dy > 0 ? "above" : "below");
        int approachDepth = target.approach().getY() - target.pos().getY();
        return target.name() + " · " + Math.round(target.distance()) + " blocks · "
            + target.pos().getX() + ", " + target.pos().getY() + ", " + target.pos().getZ()
            + " · " + vertical
            + (approachDepth > 0 ? " · approach ↓" + approachDepth : "");
    }

    private static String displayName(Block block) {
        return block.getName().getString();
    }

    private static int matchRank(String value, String needle) {
        if (value.equals(needle)) return 0;
        if (value.startsWith(needle)) return 1;
        return 2;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ').replace("minecraft:", "").trim();
    }

    public enum TargetKind { SEARCH, REFERENCE }

    public record Suggestion(Block block, String name, ResourceLocation id) {}
    public record Target(
        TargetKind kind,
        String query,
        String name,
        ResourceLocation blockId,
        String dimension,
        BlockPos pos,
        BlockPos approach,
        double distance
    ) {}
}
