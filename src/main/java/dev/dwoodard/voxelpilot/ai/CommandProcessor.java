package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.BuildSpeed;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.MoveService;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class CommandProcessor {
    private CommandProcessor() {}

    public static void run(Minecraft mc, String raw, Consumer<String> status) {
        String input = raw.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        if (input.isBlank()) return;

        switch (lower) {
            case "confirm", "confirm preview" -> {
                var result = BuildExecutor.get().confirm(mc);
                status.accept(result.message());
                return;
            }
            case "cancel", "cancel preview" -> {
                if (BuildExecutor.get().active()) BuildExecutor.get().cancel();
                GhostPreviewManager.get().clear();
                status.accept("Cancelled");
                return;
            }
            case "pause" -> { BuildExecutor.get().pause(); status.accept("Build paused"); return; }
            case "resume", "continue" -> { BuildExecutor.get().resume(); status.accept("Build resumed"); return; }
            case "undo" -> { status.accept(BuildExecutor.get().undo(mc).message()); return; }
            case "clear selection" -> { SelectionManager.get().clear(mc); status.accept("Selection cleared"); return; }
        }

        if (lower.startsWith("speed ")) {
            BuildSpeed speed = BuildSpeed.parse(lower.substring(6));
            BuildExecutor.get().setSpeed(speed);
            status.accept("Build speed: " + speed.name().toLowerCase());
            return;
        }

        BridgeServer.get().ensureRunning();
        status.accept("Planning…");
        CompletableFuture<BuildPlan> future = AiPlanner.plan(mc, input);
        future.whenComplete((plan, error) -> mc.execute(() -> {
            if (error != null) {
                status.accept("AI error: " + rootMessage(error));
                return;
            }
            if (plan.changes.isEmpty() && plan.suggestedMove != null && lower.startsWith("move me")) {
                var move = MoveService.move(mc, plan.suggestedMove.x, plan.suggestedMove.y, plan.suggestedMove.z);
                status.accept(move.message());
                return;
            }
            GhostPreviewManager.get().setPlan(plan);
            status.accept("Preview ready · " + plan.changes.size() + " changes · " + plan.mode);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] Ghost preview ready · Cmd+Shift+K for details"), true);
        }));
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
