package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.VoxelPilot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BuildExecutor {
    private static final BuildExecutor INSTANCE = new BuildExecutor();
    private final ArrayDeque<ResolvedChange> queue = new ArrayDeque<>();
    private UndoSnapshot undo;
    private UUID playerId;
    private boolean paused;
    // User-controlled only; persists across builds so "speed fast" before confirm sticks.
    private BuildSpeed speed = BuildSpeed.NORMAL;
    private int total;
    private int completed;

    // Remote servers: the build is driven from the client tick by player-style actions.
    private static final int VERIFY_DELAY_TICKS = 8;
    private static final int MINING_TIMEOUT_TICKS = 200;
    private static final int PAUSE_AFTER_REJECTS = 3;
    private record Pending(ResolvedChange change, int sentTick) {}
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Set<BlockPos> deferred = new HashSet<>();
    private final RemotePlacer placer = new RemotePlacer();
    private Map<BlockPos, BlockState> remoteUndo = new LinkedHashMap<>();
    private boolean remote;
    private int tickCount;
    private int miningTicks;
    private int failed;
    private int reoriented;
    private int consecutiveRejects;

    private BuildExecutor() {}
    public static BuildExecutor get() { return INSTANCE; }

    // Called from the client thread. Everything that reads server state (player, inventory)
    // runs on the integrated server thread; the preview is cleared back on the client once
    // the build has actually been queued.
    public CompletableFuture<Result> confirm(Minecraft mc) {
        if (mc.player == null) return CompletableFuture.completedFuture(Result.fail("No player"));
        if (GhostPreviewManager.get().drafting()) return CompletableFuture.completedFuture(Result.fail("Still planning - wait for the preview to finish"));
        var plan = GhostPreviewManager.get().plan();
        if (plan.isEmpty() || plan.get().changes().isEmpty()) return CompletableFuture.completedFuture(Result.fail("No ghost preview to confirm"));
        ResolvedPlan approved = plan.get();
        int revision = GhostPreviewManager.get().revision();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            Result result = startRemote(mc, approved);
            if (result.ok() && GhostPreviewManager.get().revision() == revision) GhostPreviewManager.get().clear();
            return CompletableFuture.completedFuture(result);
        }
        UUID player = mc.player.getUUID();
        return server.submit(() -> start(server, player, approved)).thenApplyAsync(result -> {
            // Only clear the preview we actually queued; a revision that landed meanwhile stays.
            if (result.ok() && GhostPreviewManager.get().revision() == revision) GhostPreviewManager.get().clear();
            return result;
        }, mc);
    }

    private synchronized Result start(MinecraftServer server, UUID id, ResolvedPlan approved) {
        if (!queue.isEmpty()) return Result.fail("A build is already running");
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null) return Result.fail("Player unavailable");

        if (!player.getAbilities().instabuild) {
            Map<Item, Integer> required = MaterialAnalyzer.required(approved.changes());
            if (required.containsKey(Items.AIR)) return Result.fail("Plan contains blocks that have no item and can't be placed in survival");
            List<String> missing = new ArrayList<>();
            required.forEach((item, need) -> {
                int have = MaterialAnalyzer.countInventory(player, item);
                if (have < need) missing.add(BuiltInRegistries.ITEM.getKey(item).getPath() + " " + have + "/" + need);
            });
            if (!missing.isEmpty()) {
                return Result.fail("Missing materials: " + String.join(", ", missing.subList(0, Math.min(4, missing.size())))
                    + (missing.size() > 4 ? " +" + (missing.size() - 4) + " more" : ""));
            }
        }

        // Removals first, top-down (never undercut the player's footing mid-carve), then
        // placements bottom-up so supports exist before what rests on them and a door's
        // lower half lands before its upper half.
        queue.addAll(ordered(approved.changes()));
        total = queue.size();
        completed = 0;
        paused = false;
        playerId = id;
        undo = new UndoSnapshot();
        return Result.ok("Build started · " + total + " changes · " + speed.name().toLowerCase());
    }

    private static List<ResolvedChange> ordered(List<ResolvedChange> changes) {
        List<ResolvedChange> ordered = new ArrayList<>(changes);
        ordered.sort(Comparator.<ResolvedChange>comparingInt(c -> c.removal() ? 0 : 1)
            .thenComparingInt(c -> c.removal() ? -c.pos().getY() : c.pos().getY()));
        return ordered;
    }

    private synchronized Result startRemote(Minecraft mc, ResolvedPlan approved) {
        if (active()) return Result.fail("A build is already running");
        if (mc.level == null || mc.gameMode == null) return Result.fail("No world");

        if (!mc.player.getAbilities().instabuild) {
            Map<Item, Integer> required = MaterialAnalyzer.required(approved.changes());
            if (required.containsKey(Items.AIR)) return Result.fail("Plan contains blocks that have no item and can't be placed in survival");
            List<String> missing = new ArrayList<>();
            required.forEach((item, need) -> {
                int have = MaterialAnalyzer.countInventory(mc.player, item);
                if (have < need) missing.add(BuiltInRegistries.ITEM.getKey(item).getPath() + " " + have + "/" + need);
            });
            if (!missing.isEmpty()) {
                return Result.fail("Missing materials: " + String.join(", ", missing.subList(0, Math.min(4, missing.size())))
                    + (missing.size() > 4 ? " +" + (missing.size() - 4) + " more" : ""));
            }
        }

        queue.addAll(ordered(approved.changes()));
        beginRemote(queue.size());
        remoteUndo = new LinkedHashMap<>();
        return Result.ok("Build started on server · " + total + " changes · " + speed.name().toLowerCase());
    }

    private void beginRemote(int count) {
        total = count;
        completed = 0;
        paused = false;
        remote = true;
        failed = 0;
        reoriented = 0;
        consecutiveRejects = 0;
        miningTicks = 0;
        pending.clear();
        deferred.clear();
    }

    public synchronized void pause() { paused = true; }
    public synchronized void resume() { paused = false; }
    public synchronized void cancel() {
        queue.clear();
        paused = false;
        if (remote) endRemote(Minecraft.getInstance());
    }
    public synchronized void setSpeed(BuildSpeed speed) { this.speed = speed; }
    public synchronized BuildSpeed speed() { return speed; }
    public synchronized boolean active() { return !queue.isEmpty() || !pending.isEmpty(); }
    public synchronized boolean paused() { return paused; }
    public synchronized int total() { return total; }
    public synchronized int completed() { return completed; }

    public synchronized Result undo(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return undoRemote(mc);
        if (playerId == null || undo == null || undo.size() == 0) return Result.fail("Nothing to undo");
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return Result.fail("Player unavailable");
        ServerLevel level = player.serverLevel();
        queue.clear();
        paused = false;
        UndoSnapshot snapshot = undo;
        undo = null;
        server.execute(() -> snapshot.restore(level));
        return Result.ok("Last AI operation restored");
    }

    // Restores the original block states as player actions. Container contents are not restored.
    private Result undoRemote(Minecraft mc) {
        if (mc.level == null || remoteUndo.isEmpty()) return Result.fail("Nothing to undo");
        if (remote) cancel();
        List<ResolvedChange> restore = new ArrayList<>();
        remoteUndo.forEach((pos, original) -> {
            if (mc.level.getBlockState(pos) != original) restore.add(new ResolvedChange(pos, original));
        });
        remoteUndo = new LinkedHashMap<>();
        if (restore.isEmpty()) return Result.fail("Nothing to undo");
        queue.addAll(ordered(restore));
        beginRemote(queue.size());
        return Result.ok("Restoring " + total + " blocks on server");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        synchronized (this) {
            if (!remote) return;
            if (mc.player == null || mc.level == null || mc.gameMode == null) {
                queue.clear();
                endRemote(mc);
                return;
            }
            tickCount++;
            verifyPending(mc);
            if (queue.isEmpty() && pending.isEmpty()) {
                notice(mc, "Build complete · " + completed + " changes"
                    + (failed > 0 ? " · " + failed + " rejected or skipped" : "")
                    + (reoriented > 0 ? " · " + reoriented + " placed with a different orientation" : ""));
                endRemote(mc);
                return;
            }
            if (paused || queue.isEmpty()) return;

            int cap = speed.remoteBlocksPerTick;
            for (int i = 0; i < cap && !queue.isEmpty() && !paused; i++) {
                ResolvedChange change = queue.peekFirst();
                remoteUndo.putIfAbsent(change.pos().immutable(), mc.level.getBlockState(change.pos()));
                switch (placer.step(mc, change)) {
                    case DONE -> { queue.pollFirst(); completed++; miningTicks = 0; }
                    case SENT -> { queue.pollFirst(); pending.add(new Pending(change, tickCount)); miningTicks = 0; }
                    case BUSY -> {
                        if (++miningTicks > MINING_TIMEOUT_TICKS) {
                            queue.pollFirst();
                            failed++;
                            miningTicks = 0;
                            placer.stopDestroy(mc);
                        }
                        i = cap;
                    }
                    case OUT_OF_REACH -> pauseWith(mc, "Out of reach of the next block - move closer, then /resume");
                    case NO_SUPPORT -> {
                        queue.pollFirst();
                        // Supports may land later in the build; give each block one more chance.
                        if (deferred.add(change.pos().immutable())) queue.addLast(change);
                        else failed++;
                    }
                    case MISSING_ITEM -> pauseWith(mc, "Paused: put " + itemName(placer.missing()) + " in your hotbar, then /resume");
                    case FAILED -> { queue.pollFirst(); failed++; }
                }
            }
        }
    }

    // The client predicts its own placements, so a block only counts once it is still there
    // after the server has had time to confirm or revert it.
    private void verifyPending(Minecraft mc) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (tickCount - p.sentTick() < VERIFY_DELAY_TICKS) break;
            it.remove();
            BlockState now = mc.level.getBlockState(p.change().pos());
            if (now.getBlock() == p.change().state().getBlock()) {
                completed++;
                consecutiveRejects = 0;
                if (now != p.change().state()) reoriented++;
            } else {
                failed++;
                if (++consecutiveRejects >= PAUSE_AFTER_REJECTS && !paused) {
                    pauseWith(mc, "Paused: the server is rejecting placements (protection or anti-cheat?)");
                }
            }
        }
    }

    private void pauseWith(Minecraft mc, String message) {
        paused = true;
        notice(mc, message);
    }

    private void endRemote(Minecraft mc) {
        remote = false;
        paused = false;
        pending.clear();
        placer.stopDestroy(mc);
        placer.restoreHotbar(mc);
    }

    private static void notice(Minecraft mc, String message) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] " + message), true);
    }

    private static String itemName(Item item) {
        return item == null ? "the required item" : BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        synchronized (this) {
            if (paused || queue.isEmpty() || playerId == null) return;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            int batch = Math.min(speed.blocksPerTick, queue.size());
            for (int i = 0; i < batch; i++) {
                ResolvedChange change = queue.peekFirst();
                undo.capture(level, change.pos());
                if (!MaterialAnalyzer.consumeOne(player, change.state())) {
                    paused = true;
                    player.displayClientMessage(Component.literal("[VoxelPilot] Paused: missing "
                        + BuiltInRegistries.BLOCK.getKey(change.state().getBlock()).getPath()), true);
                    break;
                }
                queue.pollFirst();
                level.setBlock(change.pos(), change.state(), 3);
                completed++;
            }

            if (queue.isEmpty() && total > 0) {
                player.displayClientMessage(Component.literal("[VoxelPilot] Build complete · " + completed + " changes"), true);
                VoxelPilot.LOGGER.info("VoxelPilot build complete: {} changes", completed);
            }
        }
    }

    public record Result(boolean ok, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }
}
