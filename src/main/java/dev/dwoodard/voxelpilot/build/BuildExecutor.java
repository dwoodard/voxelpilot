package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.VoxelPilot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(Result.fail("World editing is only available in local single-player worlds"));

        ResolvedPlan approved = plan.get();
        int revision = GhostPreviewManager.get().revision();
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
        List<ResolvedChange> ordered = new ArrayList<>(approved.changes());
        ordered.sort(Comparator.<ResolvedChange>comparingInt(c -> c.removal() ? 0 : 1)
            .thenComparingInt(c -> c.removal() ? -c.pos().getY() : c.pos().getY()));
        queue.addAll(ordered);
        total = queue.size();
        completed = 0;
        paused = false;
        playerId = id;
        undo = new UndoSnapshot();
        return Result.ok("Build started · " + total + " changes · " + speed.name().toLowerCase());
    }

    public synchronized void pause() { paused = true; }
    public synchronized void resume() { paused = false; }
    public synchronized void cancel() { queue.clear(); paused = false; }
    public synchronized void setSpeed(BuildSpeed speed) { this.speed = speed; }
    public synchronized BuildSpeed speed() { return speed; }
    public synchronized boolean active() { return !queue.isEmpty(); }
    public synchronized boolean paused() { return paused; }
    public synchronized int total() { return total; }
    public synchronized int completed() { return completed; }

    public synchronized Result undo(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || playerId == null || undo == null || undo.size() == 0) return Result.fail("Nothing to undo");
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
