package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.VoxelPilot;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.Frame;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.PlanResolver;
import dev.dwoodard.voxelpilot.build.ResolvedPlan;
import dev.dwoodard.voxelpilot.plan.PlanException;
import dev.dwoodard.voxelpilot.plan.PlanNode;
import dev.dwoodard.voxelpilot.plan.PlanScript;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.world.WorldContextService;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

// Prompt -> streamed script -> live ghost. The model writes one short command per line;
// each line is parsed, validated, and added to the draft preview the moment it arrives.
// A bad line is skipped and reported without discarding the rest. When the stream ends,
// the draft becomes a confirmable revision.
public final class AiPlanner {
    // Public (and a compile-time constant) so the offline model eval can use it without
    // loading Minecraft classes. Kept short: every token here is read on every request.
    public static final String SYSTEM_PROMPT = """
        You plan Minecraft 1.20.1 builds as a short script. Reply ONLY with script lines, one command per line.
        No markdown, no numbering, no explanations.

        Coordinates are integers: x = right, y = up, z = forward (away from the player).
        Selection [w,h,d] spans x 0..w-1, y 0..h-1, z 0..d-1. No selection: 0 0 0 is the block looked at; build on it at y 1.
        surface = local ground height (flat, or rows of z near-to-far with x left-to-right). Build on the ground: y = ground + 1.

        Commands (X Y Z is the start corner):
        box X Y Z W H D BLOCK [hollow|walls|outline]      solid by default; BLOCK air clears or digs
        stairs X Y Z DIR up|down STEPS BLOCK [width N]     DIR forward|back|left|right; going down, X Y Z is the top step
        roof X Y Z W D STAIRS_BLOCK [ends BLOCK] [across]  gable roof over W x D; across turns the ridge sideways
        door X Y Z DIR [BLOCK]
        set X Y Z BLOCK
        circle CX Y CZ R BLOCK [filled] [fill BLOCK] [thick N] [height H] [oval RZ] [even] [arc A B] [square S]
                                  flat ring centered at CX Y CZ; filled = disc; height = round wall or cylinder;
                                  oval RZ = radius along z; arc degrees 0 forward, 90 right, 180 back, 270 left
                                  (half circle ahead: arc 270 90); square 0..1 rounds toward a square
        sphere CX CY CZ R BLOCK [hollow] [dome]            dome = top half
        line X1 Y1 Z1 X2 Y2 Z2 BLOCK                     any direction, diagonals ok
        pyramid X Y Z W D BLOCK [hollow]
        NAME = <command>          names a part; "on NAME" right after the command word puts X Y Z relative to the top of NAME
        title TEXT                2-4 words
        say TEXT                  one short sentence to the player
        BLOCK is a vanilla id, optionally with state: oak_log[axis=forward]. Later lines overwrite earlier ones.

        Rules: use as few lines as possible. Vague materials get a sensible vanilla block (wood = oak_planks). No sizes given: use the whole selection. Stay inside the selection.
        Don't remove existing blocks unless asked. Not a build request: reply with only a say line.
        CURRENT SCRIPT given: output the complete revised script, changing only what was asked.

        Example, selection [7,6,9], ground at y 0:
        title Spruce cabin
        floor = box 0 0 0 7 1 9 cobblestone
        walls = box on floor 0 0 0 7 3 9 spruce_planks walls
        door 3 1 0 back
        set 0 2 4 glass_pane
        roof on walls -1 0 -1 9 11 spruce_stairs ends spruce_planks
        say Spruce cabin with the door facing you.

        Example, selection [2,30,20], ground at y 29, "dig stairs down":
        title Stairs down
        stairs 0 29 2 forward down 6 stone_brick_stairs width 2
        say Two-wide stone brick stairs going 6 down.
        """;

    // skipped = script lines the validator rejected; the rest of the plan still stands.
    public record Outcome(BuildPlan plan, ResolvedPlan resolved, Frame frame, List<String> skipped) {}

    private AiPlanner() {}

    public static CompletableFuture<Outcome> plan(Minecraft mc, String userPrompt) {
        ResolvedPlan existing = GhostPreviewManager.get().plan().orElse(null);
        Optional<Frame> current = Frame.current(mc);
        // A selection always decides where. If it differs from the one the current preview
        // was made for, this is a new operation there, not a revision of the old one.
        boolean newArea = existing != null && current.isPresent() && current.get().hasSelection() && !current.get().equals(existing.frame());
        if (newArea) GhostPreviewManager.get().clear();
        ResolvedPlan previous = newArea ? null : existing;
        // Revisions keep the original frame so the preview never shifts under the user
        // (e.g. when they look somewhere else to ask for a change).
        Optional<Frame> frame = previous != null ? Optional.of(previous.frame()) : current;
        if (frame.isEmpty()) return CompletableFuture.failedFuture(new PlanException("Look at a block or select an area with J first"));

        StringBuilder content = new StringBuilder("REQUEST: ").append(userPrompt).append('\n');
        if (previous != null && !previous.plan().script.isEmpty()) {
            content.append("\nCURRENT SCRIPT:\n").append(String.join("\n", previous.plan().script)).append('\n');
        }
        content.append("\nWORLD: ").append(WorldContextService.capture(mc, frame.get()));
        List<ChatMessage> messages = List.of(new ChatMessage("system", SYSTEM_PROMPT), new ChatMessage("user", content.toString()));
        VoxelPilot.LOGGER.info("VoxelPilot: planning request\n{}", content);

        Session session = new Session(mc, frame.get());
        long started = System.currentTimeMillis();
        return BridgeServer.get().stream(messages, line -> mc.execute(() -> session.accept(line)))
            // Line callbacks are queued with mc.execute before this completes, so by the
            // time finish() runs on the client thread every line has been applied.
            .thenCompose(text -> mc.submit(() -> {
                VoxelPilot.LOGGER.info("VoxelPilot: script in {} ms\n{}", System.currentTimeMillis() - started, text);
                return session.finish(previous, userPrompt);
            }))
            .whenComplete((outcome, error) -> {
                if (error != null) mc.execute(() -> GhostPreviewManager.get().restore(previous));
            });
    }

    // Client-thread state for one streamed answer.
    private static final class Session {
        private final Minecraft mc;
        private final Frame frame;
        private final BuildPlan plan = new BuildPlan();
        private final List<String> skipped = new ArrayList<>();
        private ResolvedPlan resolved;

        Session(Minecraft mc, Frame frame) { this.mc = mc; this.frame = frame; }

        void accept(String line) {
            PlanScript.Line parsed;
            try {
                parsed = PlanScript.parse(line);
            } catch (PlanException e) {
                skip(line, e);
                return;
            }
            if (parsed == null) return;
            if (parsed.title() != null) { plan.title = parsed.title(); return; }
            if (parsed.say() != null) { plan.message = plan.message.isEmpty() ? parsed.say() : plan.message + " " + parsed.say(); return; }
            if (parsed.move() != null) {
                BuildPlan.SuggestedMove move = new BuildPlan.SuggestedMove();
                move.x = parsed.move()[0]; move.y = parsed.move()[1]; move.z = parsed.move()[2];
                plan.suggestedMove = move;
                return;
            }
            if (mc.level == null) return;
            List<PlanNode> candidate = new ArrayList<>(plan.nodes);
            candidate.add(parsed.node());
            BuildPlan trial = new BuildPlan();
            trial.title = plan.title;
            trial.nodes = candidate;
            try {
                ResolvedPlan next = PlanResolver.resolve(mc.level, trial, frame, SelectionManager.get().box());
                plan.nodes.add(parsed.node());
                plan.script.add(line);
                resolved = next;
                GhostPreviewManager.get().showDraft(next);
            } catch (PlanException e) {
                skip(line, e);
            }
        }

        Outcome finish(ResolvedPlan previous, String userPrompt) {
            if (plan.nodes.isEmpty()) {
                GhostPreviewManager.get().restore(previous);
                if (!skipped.isEmpty() && plan.message.isEmpty()) {
                    throw new PlanException("Couldn't use the model's plan: " + skipped.get(0));
                }
                return new Outcome(plan, null, frame, skipped);
            }
            // Same changes as the last draft; rebuilt only to attach the final title, message, and script.
            ResolvedPlan last = new ResolvedPlan(plan, frame, resolved.changes(), resolved.replacedExisting(),
                resolved.outsideSelection(), resolved.min(), resolved.max(), resolved.notes());
            AiConversation.get().addUserTurn(userPrompt);
            AiConversation.get().addAssistantTurn(String.join("\n", plan.script));
            return new Outcome(plan, last, frame, skipped);
        }

        private void skip(String line, PlanException e) {
            VoxelPilot.LOGGER.warn("VoxelPilot: skipped script line '{}': {}", line, e.getMessage());
            skipped.add("'" + line + "': " + e.getMessage());
        }
    }
}
