package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.BuildSpeed;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.MoveService;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.ui.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class CommandProcessor {
    private CommandProcessor() {}

    public static void run(Minecraft mc, String raw, Consumer<String> status) {
        String input = raw.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        if (input.isBlank()) return;

        PaletteHistory.get().addUser(input);
        Consumer<String> reply = value -> { PaletteHistory.get().addAssistant(value); status.accept(value); };

        switch (lower) {
            // Short aliases so quick answers never reach the model.
            case "confirm", "confirm preview", "c", "y", "yes", "ok", "go", "build it", "do it" -> {
                BuildExecutor.get().confirm(mc).whenComplete((result, error) -> mc.execute(() ->
                    reply.accept(error != null ? "Confirm failed: " + rootMessage(error) : result.message())));
                return;
            }
            case "cancel", "cancel preview", "x", "n", "no", "stop" -> {
                if (BuildExecutor.get().active()) BuildExecutor.get().cancel();
                GhostPreviewManager.get().clear();
                reply.accept("Cancelled");
                return;
            }
            case "pause" -> { BuildExecutor.get().pause(); reply.accept("Build paused"); return; }
            case "resume", "continue" -> { BuildExecutor.get().resume(); reply.accept("Build resumed"); return; }
            case "undo", "u" -> { reply.accept(BuildExecutor.get().undo(mc).message()); return; }
            case "clear selection" -> { SelectionManager.get().clear(mc); reply.accept("Selection cleared"); return; }
            case "settings", "models", "configure" -> { mc.setScreen(new SettingsScreen()); return; }
        }

        if (lower.startsWith("speed ")) {
            BuildSpeed speed = BuildSpeed.parse(lower.substring(6));
            BuildExecutor.get().setSpeed(speed);
            reply.accept("Build speed: " + speed.name().toLowerCase());
            return;
        }

        BridgeServer.get().ensureRunning();
        status.accept("Planning…");
        AiPlanner.plan(mc, input).whenComplete((outcome, error) -> mc.execute(() -> {
            if (error != null) {
                reply.accept("AI error: " + rootMessage(error));
                return;
            }
            BuildPlan plan = outcome.plan();
            if (outcome.resolved() == null) {
                // Nothing to preview - either a move request, or the model correctly
                // recognized this wasn't a build request and replied conversationally.
                if (plan.suggestedMove != null && lower.startsWith("move me")) {
                    var target = outcome.frame().toWorld((int) Math.floor(plan.suggestedMove.x),
                        (int) Math.floor(plan.suggestedMove.y), (int) Math.floor(plan.suggestedMove.z));
                    var move = MoveService.move(mc, target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                    reply.accept(move.message());
                } else {
                    reply.accept(plan.message == null || plan.message.isBlank() ? "No changes needed" : plan.message);
                }
                return;
            }
            var resolved = outcome.resolved();
            if (resolved.changes().isEmpty()) {
                GhostPreviewManager.get().restore(null);
                reply.accept("Nothing to change - the world already matches that plan");
                return;
            }
            GhostPreviewManager.get().setPlan(resolved);
            List<String> allNotes = new ArrayList<>(resolved.notes());
            if (!outcome.skipped().isEmpty()) {
                allNotes.add("skipped " + outcome.skipped().size() + " line(s): " + outcome.skipped().get(0));
            }
            String notes = allNotes.isEmpty() ? "" : " · " + String.join(" · ", allNotes);
            reply.accept((plan.message == null || plan.message.isBlank() ? plan.title : plan.message)
                + " · rev " + GhostPreviewManager.get().revision() + " · " + resolved.changes().size() + " changes" + notes);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] Ghost preview ready · Cmd+Shift+K for details"), true);
        }));
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
