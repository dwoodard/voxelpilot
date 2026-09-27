package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.BuildSpeed;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.PreviewMover;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import dev.dwoodard.voxelpilot.reference.ReferenceStore;
import dev.dwoodard.voxelpilot.reference.ReferencePins;
import dev.dwoodard.voxelpilot.query.DeterministicQueryService;
import dev.dwoodard.voxelpilot.selection.StructureSelector;
import dev.dwoodard.voxelpilot.ui.SettingsScreen;
import dev.dwoodard.voxelpilot.ui.ShortcutRegistry;
import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
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

        if (lower.equals("/shortcuts") || lower.equals("/keys")) {
            reply.accept(ShortcutRegistry.displayText());
            return;
        }

        if (input.startsWith("?")) {
            reply.accept(DeterministicQueryService.query(mc, input.substring(1)));
            return;
        }

        if (lower.startsWith("/pin ")) {
            String token = input.substring(input.indexOf(' ') + 1).trim();
            ReferenceResolver.resolve(mc, token.startsWith("@") ? token.substring(1) : token)
                .ifPresentOrElse(
                    reference -> {
                        ReferencePins.get().pin(reference.token());
                        reply.accept("Pinned " + reference.token() + " to HUD");
                    },
                    () -> reply.accept("Unknown reference: " + token)
                );
            return;
        }

        if (lower.startsWith("/unpin ")) {
            String token = input.substring(input.indexOf(' ') + 1).trim();
            reply.accept(ReferencePins.get().unpin(token)
                ? "Unpinned " + (token.startsWith("@") ? token : "@" + token)
                : "Reference was not pinned");
            return;
        }

        if (input.startsWith("#") && !input.contains(" ")) {
            ReferenceStore.get().designate(mc, input.substring(1))
                .ifPresentOrElse(
                    place -> reply.accept("Designated @" + place.name() + " · " + place.position().getX() + ", "
                        + place.position().getY() + ", " + place.position().getZ()),
                    () -> reply.accept("Could not create designation")
                );
            return;
        }

        if (input.startsWith("@") && !input.contains(" ")) {
            ReferenceResolver.resolve(mc, input.substring(1))
                .ifPresentOrElse(
                    reference -> reply.accept(reference.promptContext().replace("\n", " · ")),
                    () -> reply.accept(input + " is unknown")
                );
            return;
        }

        if (lower.equals("/wayfinder next")) {
            var target = WayfinderManager.get().findNext(mc);
            if (target.isEmpty()) {
                reply.accept(WayfinderManager.get().canFindNext()
                    ? WayfinderManager.get().searchFailure("additional " + WayfinderManager.get().active().map(WayfinderManager.Target::query).orElse("match"))
                    : "Find Next requires an active Wayfinder search");
            } else {
                reply.accept(WayfinderManager.get().describe(target.get(), mc.player.blockPosition()));
                mc.setScreen(null);
                if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("[VoxelPilot] Wayfinding to next " + target.get().name()), true);
            }
            return;
        }

        if (lower.equals("/wayfinder") || lower.equals("/wayfinder cancel") || lower.equals("/wayfinder clear")) {
            WayfinderManager.get().clear();
            reply.accept(lower.equals("/wayfinder") ? "Usage: /wayfinder [block]" : "Wayfinder cleared");
            return;
        }
        if (lower.startsWith("/wayfinder ")) {
            String query = input.substring(input.indexOf(' ') + 1).trim();
            var target = query.startsWith("@")
                ? WayfinderManager.get().followReference(mc, query)
                : WayfinderManager.get().findNearest(mc, query);
            if (target.isEmpty()) {
                reply.accept(query.startsWith("@")
                    ? query + " has no known position in the current dimension"
                    : WayfinderManager.get().searchFailure(query));
            } else {
                reply.accept(WayfinderManager.get().describe(target.get(), mc.player.blockPosition()));
                mc.setScreen(null);
                if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("[VoxelPilot] Wayfinding to " + target.get().name()), true);
            }
            return;
        }

        switch (lower) {
            // World mutation requires explicit preview confirmation. Conversational text such as
            // "yes", "go", or "do it" belongs to AI rather than acting as authorization.
            case "confirm", "confirm preview" -> {
                BuildExecutor.get().confirm(mc).whenComplete((result, error) -> mc.execute(() -> {
                    if (error == null && result.ok()) RecentHistory.get().mark("confirmed");
                    else RecentHistory.get().mark("confirm failed: " + (error != null ? rootMessage(error) : result.message()));
                    reply.accept(error != null ? "Confirm failed: " + rootMessage(error) : result.message());
                }));
                return;
            }
            case "cancel preview", "clear preview", "clear ghost", "remove preview", "clear the preview" -> {
                if (BuildExecutor.get().active()) BuildExecutor.get().cancel();
                GhostPreviewManager.get().clear();
                RecentHistory.get().mark("cancelled");
                reply.accept("Cancelled");
                return;
            }
            case "pause build" -> { BuildExecutor.get().pause(); reply.accept("Build paused"); return; }
            case "resume build" -> { BuildExecutor.get().resume(); reply.accept("Build resumed"); return; }
            case "undo build" -> {
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

        // Natural language search queries should use Wayfinder, not the AI model.
        // The AI model wastes tokens thinking about building when asked "find closest X".
        var search = java.util.regex.Pattern.compile("(?:find|locate|search for|where is) (?:the |closest |nearest )?(\\S+)").matcher(lower);
        if (search.matches()) {
            String query = search.group(1);
            var target = WayfinderManager.get().findNearest(mc, query);
            if (target.isEmpty()) {
                reply.accept(WayfinderManager.get().searchFailure(query));
            } else {
                reply.accept(WayfinderManager.get().describe(target.get(), mc.player.blockPosition()));
                mc.setScreen(null);
                if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("[VoxelPilot] Wayfinding to " + target.get().name()), true);
            }
            return;
        }

        BridgeServer.get().ensureRunning();
        status.accept("Planning…");
        RecentHistory.Entry history = RecentHistory.get().start(input);
        AiService.run(mc, input).whenComplete((result, error) -> mc.execute(() -> {
            if (error != null) {
                history.fail("error: " + rootMessage(error));
                reply.accept("AI error: " + rootMessage(error));
                return;
            }
            if (result instanceof AiResult.Answer answer) {
                history.finish(List.of(), "replied: " + answer.message());
                reply.accept(answer.message());
                return;
            }
            AiResult.Build build = (AiResult.Build) result;
            BuildPlan plan = build.plan();
            AiPlanner.Outcome outcome = new AiPlanner.Outcome(plan, build.resolved(), build.frame(), build.skipped());
            history.finish(plan.script, describe(outcome));
            if (outcome.resolved() == null) {
                // AI may suggest movement, but suggestions never directly mutate player state.
                // Movement needs its own explicit deterministic/authorized capability.
                if (plan.suggestedMove != null) {
                    reply.accept("Movement suggested but not executed");
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
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] Ghost preview ready · Cmd+K for actions"), true);
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
