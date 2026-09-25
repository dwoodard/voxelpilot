package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.BuildSpeed;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.MoveService;
import dev.dwoodard.voxelpilot.build.PreviewMover;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.selection.StructureSelector;
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

        if (lower.equals("clear chat")) {
            PaletteHistory.get().clear();
            RecentHistory.get().clear();
            status.accept("Type what you want VoxelPilot to do");
            return;
        }
        // Full reset: ghost, chat, and everything the AI remembers. The selection stays.
        if (lower.equals("cls") || lower.equals("reset") || lower.equals("clear all") || lower.equals("start over")) {
            GhostPreviewManager.get().clear();
            PaletteHistory.get().clear();
            RecentHistory.get().clear();
            status.accept("Reset · preview, chat, and AI history cleared");
            return;
        }

        PaletteHistory.get().addUser(input);
        Consumer<String> reply = value -> { PaletteHistory.get().addAssistant(value); status.accept(value); };

        switch (lower) {
            // Short aliases so quick answers never reach the model.
            case "confirm", "confirm preview", "c", "y", "yes", "ok", "go", "build it", "do it" -> {
                BuildExecutor.get().confirm(mc).whenComplete((result, error) -> mc.execute(() -> {
                    if (error == null && result.ok()) RecentHistory.get().mark("confirmed");
                    else RecentHistory.get().mark("confirm failed: " + (error != null ? rootMessage(error) : result.message()));
                    reply.accept(error != null ? "Confirm failed: " + rootMessage(error) : result.message());
                }));
                return;
            }
            case "cancel", "cancel preview", "x", "n", "no", "stop", "clear preview", "clear ghost", "remove preview", "clear the preview" -> {
                if (BuildExecutor.get().active()) BuildExecutor.get().cancel();
                GhostPreviewManager.get().clear();
                RecentHistory.get().mark("cancelled");
                reply.accept("Cancelled");
                return;
            }
            case "pause" -> { BuildExecutor.get().pause(); reply.accept("Build paused"); return; }
            case "resume", "continue" -> { BuildExecutor.get().resume(); reply.accept("Build resumed"); return; }
            case "undo", "u" -> {
                var result = BuildExecutor.get().undo(mc);
                if (result.ok()) RecentHistory.get().mark("undone");
                reply.accept(result.message());
                return;
            }
            case "clear selection" -> { SelectionManager.get().clear(mc); reply.accept("Selection cleared"); return; }
            case "settings", "models", "configure" -> { mc.setScreen(new SettingsScreen()); return; }
        }

        // Pointing is unambiguous, so it needs no model: grow from the crosshair.
        if (lower.matches("select (this|that|it|what i'?m looking at)( one| thing| structure| build)?")) {
            StructureSelector.selectLookedAt(mc).ifPresent(reply);
            return;
        }

        // "select the dirt" / "select all connected stone" / "select same": when the named block
        // is the one under the crosshair, grab every connected block of that kind. Otherwise
        // (a description, not what's pointed at) it falls through to the AI.
        if (lower.equals("select same") || lower.equals("select all like this")) {
            StructureSelector.selectConnectedSame(mc, null).ifPresent(reply);
            return;
        }
        var named = java.util.regex.Pattern.compile("select (?:all |the |connected |this |that )*(.+?)(?: blocks?)?").matcher(lower);
        if (named.matches()) {
            var result = StructureSelector.selectConnectedSame(mc, named.group(1));
            if (result.isPresent()) { reply.accept(result.get()); return; }
        }

        // Moving or turning the preview is geometry, not a new plan: no model needed.
        if (GhostPreviewManager.get().hasPreview()) {
            var move = java.util.regex.Pattern.compile("(?:move|shift|nudge|slide)(?: it| the preview| preview)? (left|right|forward|forwards|back|backward|backwards|up|down)(?: (\\d+))?(?: blocks?)?").matcher(lower);
            if (move.matches()) {
                int n = move.group(2) == null ? 1 : Math.min(256, Integer.parseInt(move.group(2)));
                String result = switch (move.group(1)) {
                    case "left" -> PreviewMover.move(mc, -n, 0, 0);
                    case "right" -> PreviewMover.move(mc, n, 0, 0);
                    case "forward", "forwards" -> PreviewMover.move(mc, 0, 0, n);
                    case "up" -> PreviewMover.move(mc, 0, n, 0);
                    case "down" -> PreviewMover.move(mc, 0, -n, 0);
                    default -> PreviewMover.move(mc, 0, 0, -n);
                };
                reply.accept(result);
                return;
            }
            switch (lower) {
                case "rotate", "rotate it", "rotate right", "rotate it right", "turn right", "turn it right" -> { reply.accept(PreviewMover.rotate(mc, 1)); return; }
                case "rotate left", "rotate it left", "turn left", "turn it left" -> { reply.accept(PreviewMover.rotate(mc, -1)); return; }
                case "turn around", "turn it around", "rotate 180", "flip it around" -> { reply.accept(PreviewMover.rotate(mc, 2)); return; }
                case "move here", "move it here", "put it here", "place it here" -> { reply.accept(PreviewMover.moveHere(mc)); return; }
                case "drop", "drop it", "snap to ground", "put it on the ground", "ground it" -> { reply.accept(PreviewMover.drop(mc)); return; }
            }
        }

        if (lower.startsWith("speed ")) {
            BuildSpeed speed = BuildSpeed.parse(lower.substring(6));
            BuildExecutor.get().setSpeed(speed);
            reply.accept("Build speed: " + speed.name().toLowerCase());
            return;
        }

        BridgeServer.get().ensureRunning();
        status.accept("Planning…");
        RecentHistory.Entry history = RecentHistory.get().start(input);
        AiPlanner.plan(mc, input).whenComplete((outcome, error) -> mc.execute(() -> {
            if (error != null) {
                history.fail("error: " + rootMessage(error));
                reply.accept("AI error: " + rootMessage(error));
                return;
            }
            BuildPlan plan = outcome.plan();
            history.finish(plan.script, describe(outcome));
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

    // What happened, in the terms the model needs next time.
    private static String describe(AiPlanner.Outcome outcome) {
        StringBuilder s = new StringBuilder();
        if (outcome.resolved() == null) {
            s.append(outcome.plan().message == null || outcome.plan().message.isBlank() ? "no changes" : "replied: " + outcome.plan().message);
        } else {
            s.append("previewed ").append(outcome.resolved().changes().size()).append(" changes");
        }
        for (String skipped : outcome.skipped()) s.append("; skipped ").append(skipped);
        return s.toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
