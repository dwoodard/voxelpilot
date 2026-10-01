package dev.dwoodard.voxelpilot.wayfinder;

import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import dev.dwoodard.voxelpilot.reference.ObservationState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class WayfinderManager {
    private static final WayfinderManager INSTANCE = new WayfinderManager();
    private static final int SEARCH_CHUNK_RADIUS = 1000;
    private static final int PREVIEW_CHUNK_RADIUS = 2;

    private Target active;
    private Set<BlockPos> previousSearchResults = Set.of();
    private SearchCoverage lastSearchCoverage;
    private List<BlockPos> waypoints = List.of();

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
            ref.dimension(), pos, pos, distance, List.of());
        computePathAsync(mc, target);
        previousSearchResults = Set.of();
        active = target;
        return Optional.of(target);
    }

    // Jumps straight to a specific position a caller already knows about (e.g. one of
    // previewNearby's candidates), rather than re-running a text search that might land on a
    // different match than the one actually selected. blockId is null since there's nothing
    // to "find again" here -- canFindNext()/findNext() already treat that as "no further match".
    public Optional<Target> goTo(Minecraft mc, String name, BlockPos pos) {
        if (mc == null || mc.player == null || mc.level == null) return Optional.empty();
        BlockPos approach = approachPosition(mc, pos);
        double distance = Math.sqrt(mc.player.blockPosition().distSqr(pos));
        Target target = new Target(TargetKind.SEARCH, name, name, null,
            mc.level.dimension().location().toString(), pos.immutable(), approach, distance, List.of());
        computePathAsync(mc, target);
        previousSearchResults = Set.of();
        active = target;
        return Optional.of(target);
    }

    // A cheap, small-radius scan for the live Cmd-K suggestion list while typing "/wayfinder
    // <query>" -- unlike findNearest/findSearchTarget's full SEARCH_CHUNK_RADIUS scan (reserved
    // for pressing Enter), this runs on every keystroke so it stays local and returns several
    // candidates instead of just the closest one.
    public List<PreviewMatch> previewNearby(Minecraft mc, String query, int limit) {
        if (mc == null || mc.player == null || mc.level == null) return List.of();
        String needle = normalize(query);
        // Only once the typed text is a complete, resolved name (typed in full or reached via
        // Tab) -- not a partial fragment like "dia" -- so the list doesn't flood with raw
        // nearby matches before the player has actually said what they're looking for. This
        // also makes backing out remove them for free: deleting a character back out of an
        // exact match makes this fail again on the very next keystroke.
        if (needle.isBlank() || !isResolvedName(needle)) return List.of();

        BlockPos player = mc.player.blockPosition();
        int playerChunkX = player.getX() >> 4;
        int playerChunkZ = player.getZ() >> 4;

        record Candidate(String name, BlockPos pos, double distSq) {}
        List<Candidate> candidates = new ArrayList<>();

        Set<Block> wantedBlocks = suggestions(query, 6).stream()
            .map(Suggestion::block)
            .collect(java.util.stream.Collectors.toSet());

        for (int cx = playerChunkX - PREVIEW_CHUNK_RADIUS; cx <= playerChunkX + PREVIEW_CHUNK_RADIUS; cx++) {
            for (int cz = playerChunkZ - PREVIEW_CHUNK_RADIUS; cz <= playerChunkZ + PREVIEW_CHUNK_RADIUS; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);

                if (!wantedBlocks.isEmpty()) {
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                        LevelChunkSection section = sections[sectionIndex];
                        if (section == null || section.hasOnlyAir()) continue;
                        int baseY = SectionPos.sectionToBlockCoord(mc.level.getSectionYFromSectionIndex(sectionIndex));
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    Block block = section.getBlockState(x, y, z).getBlock();
                                    if (!wantedBlocks.contains(block)) continue;
                                    BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                                    candidates.add(new Candidate(displayName(block), pos, player.distSqr(pos)));
                                }
                            }
                        }
                    }
                }

                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof net.minecraft.world.Container container)) continue;
                    BlockPos pos = blockEntity.getBlockPos();
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        var stack = container.getItem(slot);
                        if (stack.isEmpty()) continue;
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        String itemPath = id == null ? "" : normalize(id.getPath());
                        if (!itemPath.contains(needle) && !normalize(stack.getHoverName().getString()).contains(needle)) continue;
                        String containerName = mc.level.getBlockState(pos).getBlock().getName().getString();
                        candidates.add(new Candidate(stack.getHoverName().getString() + " (in " + containerName + ")", pos, player.distSqr(pos)));
                        break;
                    }
                }
            }
        }

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            BlockPos pos = entity.blockPosition();
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            if (Math.abs(cx - playerChunkX) > PREVIEW_CHUNK_RADIUS || Math.abs(cz - playerChunkZ) > PREVIEW_CHUNK_RADIUS) continue;
            ResourceLocation entityTypeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (entityTypeId == null || !normalize(entityTypeId.getPath()).contains(needle)) continue;
            candidates.add(new Candidate(entity.getDisplayName().getString(), pos, player.distSqr(pos)));
        }

        return candidates.stream()
            .sorted(Comparator.comparingDouble(Candidate::distSq))
            .limit(limit)
            .map(c -> {
                var local = dev.dwoodard.voxelpilot.util.RelativeBearing.toLocal(mc,
                    c.pos().getX() - player.getX(), c.pos().getZ() - player.getZ());
                int right = (int) Math.round(local.right());
                int forward = (int) Math.round(local.forward());
                int up = c.pos().getY() - player.getY();
                return new PreviewMatch(c.name(), c.pos(), right, forward, up, Math.sqrt(c.distSq()));
            })
            .toList();
    }

    public Optional<Target> findNearest(Minecraft mc, String query) {
        previousSearchResults = Set.of();
        clearWaypoints();
        return findSearchTarget(mc, query, previousSearchResults, true);
    }

    public Optional<Target> findNext(Minecraft mc) {
        if (active == null || active.kind() != TargetKind.SEARCH || active.blockId() == null) return Optional.empty();
        Target previous = active;
        previousSearchResults = new java.util.HashSet<>(previousSearchResults);
        previousSearchResults.add(previous.pos());
        Optional<Target> next = findSearchTarget(mc, previous.query(), previousSearchResults, true);
        if (next.isEmpty()) active = previous;
        return next;
    }

    public boolean canFindNext() {
        return active != null && active.kind() == TargetKind.SEARCH;
    }

    public Optional<Target> inspectNearest(Minecraft mc, String query) {
        return findSearchTarget(mc, query, Set.of(), false);
    }

    private Optional<Target> findSearchTarget(Minecraft mc, String query, Set<BlockPos> excluded, boolean activate) {
        if (mc.player == null || mc.level == null) return Optional.empty();

        BlockPos player = mc.player.blockPosition();
        int playerChunkX = player.getX() >> 4;
        int playerChunkZ = player.getZ() >> 4;

        Optional<Target> blockTarget = findBlockTarget(mc, query, excluded, playerChunkX, playerChunkZ);
        Optional<Target> entityTarget = findEntityTarget(mc, query, playerChunkX, playerChunkZ);
        Optional<Target> containerTarget = findContainerTarget(mc, query, excluded, playerChunkX, playerChunkZ);

        Optional<Target> best = Optional.empty();
        for (Optional<Target> candidate : List.of(blockTarget, entityTarget, containerTarget)) {
            if (candidate.isEmpty()) continue;
            if (best.isEmpty() || candidate.get().distance() < best.get().distance()) best = candidate;
        }

        if (best.isPresent() && activate) active = best.get();
        return best;
    }

    private Optional<Target> findBlockTarget(Minecraft mc, String query, Set<BlockPos> excluded, int playerChunkX, int playerChunkZ) {
        if (mc.player == null || mc.level == null) return Optional.empty();

        List<Suggestion> matches = suggestions(query, 12);
        if (matches.isEmpty()) return Optional.empty();

        Block wanted = matches.get(0).block();
        ResourceLocation wantedId = ForgeRegistries.BLOCKS.getKey(wanted);
        BlockPos player = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int loadedChunks = 0;

        for (int cx = playerChunkX - SEARCH_CHUNK_RADIUS; cx <= playerChunkX + SEARCH_CHUNK_RADIUS; cx++) {
            for (int cz = playerChunkZ - SEARCH_CHUNK_RADIUS; cz <= playerChunkZ + SEARCH_CHUNK_RADIUS; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                loadedChunks++;
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

        lastSearchCoverage = new SearchCoverage(
            mc.level.dimension().location().toString(),
            playerChunkX - SEARCH_CHUNK_RADIUS, playerChunkZ - SEARCH_CHUNK_RADIUS,
            playerChunkX + SEARCH_CHUNK_RADIUS, playerChunkZ + SEARCH_CHUNK_RADIUS,
            loadedChunks
        );
        if (best == null) return Optional.empty();
        BlockPos approach = approachPosition(mc, best);
        Target target = new Target(TargetKind.SEARCH, query, displayName(wanted), wantedId,
            mc.level.dimension().location().toString(), best.immutable(), approach, Math.sqrt(bestDistance), List.of());
        computePathAsync(mc, target);
        return Optional.of(target);
    }

    // Chests, barrels, shulker boxes, hoppers, dispensers, furnaces -- anything implementing
    // Container. Only loaded chunks' block entities are checked (a small set per chunk, unlike
    // scanning every block), so this stays cheap even at the full search radius.
    private Optional<Target> findContainerTarget(Minecraft mc, String query, Set<BlockPos> excluded, int playerChunkX, int playerChunkZ) {
        if (mc.player == null || mc.level == null) return Optional.empty();

        String needle = normalize(query);
        if (needle.isBlank()) return Optional.empty();

        BlockPos player = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        net.minecraft.world.item.ItemStack bestStack = null;

        for (int cx = playerChunkX - SEARCH_CHUNK_RADIUS; cx <= playerChunkX + SEARCH_CHUNK_RADIUS; cx++) {
            for (int cz = playerChunkZ - SEARCH_CHUNK_RADIUS; cz <= playerChunkZ + SEARCH_CHUNK_RADIUS; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);

                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof net.minecraft.world.Container container)) continue;
                    BlockPos pos = blockEntity.getBlockPos();
                    if (excluded.contains(pos)) continue;
                    double distance = player.distSqr(pos);
                    if (distance >= bestDistance) continue;

                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        var stack = container.getItem(slot);
                        if (stack.isEmpty()) continue;
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        String itemPath = id == null ? "" : normalize(id.getPath());
                        if (!itemPath.contains(needle) && !normalize(stack.getHoverName().getString()).contains(needle)) continue;
                        bestDistance = distance;
                        best = pos;
                        bestStack = stack;
                        break;
                    }
                }
            }
        }

        if (best == null) return Optional.empty();
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(bestStack.getItem());
        String containerName = mc.level.getBlockState(best).getBlock().getName().getString();
        String name = bestStack.getHoverName().getString() + " (in " + containerName + ")";
        BlockPos approach = approachPosition(mc, best);
        Target target = new Target(TargetKind.SEARCH, query, name, itemId,
            mc.level.dimension().location().toString(), best.immutable(), approach, Math.sqrt(bestDistance), List.of());
        computePathAsync(mc, target);
        return Optional.of(target);
    }

    private Optional<Target> findEntityTarget(Minecraft mc, String query, int playerChunkX, int playerChunkZ) {
        if (mc.player == null || mc.level == null) return Optional.empty();

        String needle = normalize(query);
        if (needle.isBlank()) return Optional.empty();

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos player = mc.player.blockPosition();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;

            int cx = entity.blockPosition().getX() >> 4;
            int cz = entity.blockPosition().getZ() >> 4;
            if (Math.abs(cx - playerChunkX) > SEARCH_CHUNK_RADIUS || Math.abs(cz - playerChunkZ) > SEARCH_CHUNK_RADIUS) continue;

            ResourceLocation entityTypeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (entityTypeId == null) continue;

            String entityPath = normalize(entityTypeId.getPath());
            String entityFull = normalize(entityTypeId.toString());
            if (entityPath.contains(needle) || entityFull.contains(needle)) {
                double distance = player.distSqr(entity.blockPosition());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entity;
                }
            }
        }

        if (best == null) return Optional.empty();

        BlockPos entityPos = best.blockPosition();
        BlockPos approach = approachPosition(mc, entityPos);
        String displayName = best.getDisplayName().getString();
        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(best.getType());

        Target target = new Target(TargetKind.SEARCH, query, displayName, entityId,
            mc.level.dimension().location().toString(), entityPos, approach, Math.sqrt(bestDistance), List.of());
        computePathAsync(mc, target);
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

    public List<BlockPos> waypoints() { return waypoints; }

    public void addWaypoint(BlockPos pos) {
        if (!waypoints.contains(pos)) {
            waypoints = new ArrayList<>(waypoints);
            waypoints.add(pos);
        }
    }

    public void clearWaypoints() { waypoints = List.of(); }

    public ObservationState observationState(Minecraft mc, Target target) {
        if (mc == null || mc.level == null || target == null || target.kind() != TargetKind.SEARCH
            || target.blockId() == null) return ObservationState.UNKNOWN;
        if (!mc.level.dimension().location().toString().equals(target.dimension())) return ObservationState.UNKNOWN;
        if (!mc.level.hasChunkAt(target.pos())) return ObservationState.UNKNOWN;

        ResourceLocation current = ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(target.pos()).getBlock());
        if (target.blockId().equals(current)) return ObservationState.PRESENT;

        for (Entity entity : mc.level.entitiesForRendering()) {
            ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (target.blockId().equals(entityId)) return ObservationState.PRESENT;
        }

        // A container match's blockId is the item, not the chest/barrel block itself, so the
        // block-equality check above never applies to it -- look inside instead.
        var blockEntity = mc.level.getBlockEntity(target.pos());
        if (blockEntity instanceof net.minecraft.world.Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (!stack.isEmpty() && target.blockId().equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) {
                    return ObservationState.PRESENT;
                }
            }
        }

        return ObservationState.NO_LONGER_PRESENT;
    }

    public String searchFailure(String query) {
        if (lastSearchCoverage == null) return "No " + query + " detected; search coverage is unknown";
        return "No " + query + " detected in " + lastSearchCoverage.loadedChunks()
            + " loaded chunks within the searched " + lastSearchCoverage.widthChunks()
            + "x" + lastSearchCoverage.depthChunks() + " chunk area";
    }

    public void clear() {
        active = null;
        previousSearchResults = Set.of();
        clearWaypoints();
    }

    private void computePathAsync(Minecraft mc, Target target) {
        Thread thread = new Thread(() -> {
            if (mc.player == null) return;
            BlockPos playerPos = mc.player.blockPosition();
            System.out.println("[VoxelPilot] Pathfinding: " + playerPos + " -> " + target.approach());
            var path = PathfindingEngine.findPath(mc, playerPos, target.approach(), 1000);
            System.out.println("[VoxelPilot] Path result: " + (path.isEmpty() ? "empty" : path.get().size() + " waypoints"));
            if (path.isPresent() && active == target) {
                System.out.println("[VoxelPilot] Updating active target with path");
                List<BlockPos> pathList = path.get();
                active = new Target(target.kind(), target.query(), target.name(), target.blockId(),
                    target.dimension(), target.pos(), target.approach(), target.distance(), pathList);

                if (!pathList.isEmpty()) {
                    BlockPos lastWaypoint = pathList.get(pathList.size() - 1);
                    if (!lastWaypoint.equals(target.approach())) {
                        addWaypoint(lastWaypoint);
                    }
                }
            } else {
                System.out.println("[VoxelPilot] Not updating: path.isPresent=" + path.isPresent() + ", active==target=" + (active == target));
            }
        });
        thread.setName("VoxelPilot-Pathfinding");
        thread.setDaemon(true);
        thread.start();
    }

    public void recomputePath(Minecraft mc, Target target) {
        if (mc.player == null || !active().map(t -> t == target).orElse(false)) return;

        Thread thread = new Thread(() -> {
            if (mc.player == null) return;
            BlockPos playerPos = mc.player.blockPosition();
            var path = PathfindingEngine.findPath(mc, playerPos, target.approach(), 1000);
            if (path.isPresent() && active == target) {
                List<BlockPos> pathList = path.get();
                active = new Target(target.kind(), target.query(), target.name(), target.blockId(),
                    target.dimension(), target.pos(), target.approach(), target.distance(), pathList);

                if (!pathList.isEmpty()) {
                    BlockPos lastWaypoint = pathList.get(pathList.size() - 1);
                    if (!lastWaypoint.equals(target.approach())) {
                        if (!waypoints.contains(lastWaypoint)) {
                            addWaypoint(lastWaypoint);
                        }
                    }
                }
            }
        });
        thread.setName("VoxelPilot-PathUpdate");
        thread.setDaemon(true);
        thread.start();
    }

    public String describe(Target target, BlockPos player) {
        int dy = target.pos().getY() - player.getY();
        String depthIndicator = dy == 0 ? "" : (dy > 0 ? " ⬆ " : " ⬇ ") + Math.abs(dy);
        return target.name() + " · " + Math.round(target.distance()) + "m" + depthIndicator;
    }

    private static String displayName(Block block) {
        return block.getName().getString();
    }

    // True once `needle` (already normalized) exactly names a real block or item -- by id path
    // or display name -- rather than merely being a prefix/substring of one.
    private static boolean isResolvedName(String needle) {
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            if (normalize(displayName(block)).equals(needle)) return true;
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id != null && normalize(id.getPath()).equals(needle)) return true;
        }
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null && normalize(id.getPath()).equals(needle)) return true;
            if (normalize(item.getDescription().getString()).equals(needle)) return true;
        }
        return false;
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
    public record PreviewMatch(String name, BlockPos pos, int right, int forward, int up, double distance) {}
    public record SearchCoverage(String dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, int loadedChunks) {
        public int widthChunks() { return maxChunkX - minChunkX + 1; }
        public int depthChunks() { return maxChunkZ - minChunkZ + 1; }
    }
    public record Target(
        TargetKind kind,
        String query,
        String name,
        ResourceLocation blockId,
        String dimension,
        BlockPos pos,
        BlockPos approach,
        double distance,
        List<BlockPos> path
    ) {}
}
