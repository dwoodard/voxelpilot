package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.VoxelPilot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class BuildExecutor {
    private static final BuildExecutor INSTANCE = new BuildExecutor();
    private final List<GhostPreviewManager.ResolvedChange> queue = new ArrayList<>();
    private UndoSnapshot undo;
    private UUID playerId;
    private boolean paused;
    private BuildSpeed speed = BuildSpeed.NORMAL;
    private int total;
    private int completed;

    private BuildExecutor() {}
    public static BuildExecutor get() { return INSTANCE; }

    public synchronized Result confirm(Minecraft mc) {
        if (mc.player == null || !GhostPreviewManager.get().hasPreview()) return Result.fail("No ghost preview to confirm");
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return Result.fail("World editing is only available in local single-player worlds");
        if (!queue.isEmpty()) return Result.fail("A build is already running");

        List<GhostPreviewManager.ResolvedChange> preview = GhostPreviewManager.get().changes();
        ServerPlayer resourcePlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (resourcePlayer == null) return Result.fail("Player unavailable");
        if (!resourcePlayer.getAbilities().instabuild) {
            var required = MaterialAnalyzer.required(preview);
            String missing = required.entrySet().stream()
                .filter(e -> MaterialAnalyzer.countInventory(resourcePlayer, e.getKey()) < e.getValue())
                .map(e -> e.getKey() + " " + MaterialAnalyzer.countInventory(resourcePlayer, e.getKey()) + "/" + e.getValue())
                .limit(4)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            if (!missing.isBlank()) return Result.fail("Missing materials: " + missing);
        }

        queue.addAll(preview);
        queue.sort(Comparator.comparingInt(c -> "minecraft:air".equals(c.blockId()) ? -c.pos().getY() : c.pos().getY()));
        total = queue.size();
        completed = 0;
        paused = false;
        playerId = mc.player.getUUID();
        speed = GhostPreviewManager.get().plan().map(p -> BuildSpeed.parse(p.speed)).orElse(BuildSpeed.NORMAL);
        undo = new UndoSnapshot();
        GhostPreviewManager.get().clear();
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
                var change = queue.remove(0);
                undo.capture(level, change.pos());
                if (!MaterialAnalyzer.consumeOne(player, change.blockId())) {
                    queue.add(0, change);
                    paused = true;
                    player.sendSystemMessage(Component.literal("[VoxelPilot] Paused: missing " + change.blockId()));
                    break;
                }

                BlockState state = resolve(change.blockId());
                level.setBlock(change.pos(), state, 3);
                completed++;
            }

            if (queue.isEmpty() && total > 0) {
                player.sendSystemMessage(Component.literal("[VoxelPilot] Build complete · " + completed + " changes"));
                VoxelPilot.LOGGER.info("VoxelPilot build complete: {} changes", completed);
            }
        }
    }

    private BlockState resolve(String idText) {
        if ("minecraft:air".equals(idText)) return Blocks.AIR.defaultBlockState();
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.AIR.defaultBlockState();
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block.defaultBlockState();
    }

    public record Result(boolean ok, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }
}
