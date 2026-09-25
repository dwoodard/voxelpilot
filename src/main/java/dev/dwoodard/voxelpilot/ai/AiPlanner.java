package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.VoxelPilot;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.Frame;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.PlanResolver;
import dev.dwoodard.voxelpilot.build.ResolvedPlan;
import dev.dwoodard.voxelpilot.plan.PlanException;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.world.WorldContextService;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

// Prompt -> model -> component plan -> local validation -> ResolvedPlan. The model only
// chooses and sizes components; geometry, block states, and orientation are computed
// locally. A plan the validator rejects is sent back once with the exact error so the
// model can fix it, instead of failing the user on a typo.
public final class AiPlanner {
    private static final int REPAIR_ATTEMPTS = 1;

    // Public (and a compile-time constant) so the offline model eval can use it without
    // loading Minecraft classes.
    public static final String SYSTEM_PROMPT = """
        You are VoxelPilot, the planning layer of a Minecraft 1.20.1 building tool. You never edit the world.
        You describe a build as a short list of components. The mod expands them into exact blocks, shows a
        ghost preview, and the player decides whether to build it.

        COORDINATES (integers, local frame):
        - x = right, y = up, z = forward (away from the player). Never use north/south/east/west.
        - With a selection of size [w,h,d] it spans x 0..w-1, y 0..h-1, z 0..d-1. y=0 is its lowest layer.
        - With no selection, [0,0,0] is the block the player is looking at ("here"); build on top of it at y=1.
        - WORLD CONTEXT "surface.heights" is the local y of the ground per column: rows are z (near to far),
          columns are x (left to right), starting at surface.from, every surface.stride blocks. null = no ground.
          Floors usually go at ground height + 1 unless the user wants the ground replaced.

        COMPONENTS (only the listed fields matter):
        - box: at, size [w,h,d], block, fill = solid | hollow (shell, inside cleared) | walls (4 sides only) |
          outline (edges only). Platforms, floors, walls, pillars. Use block "air" to clear or dig out a space.
        - stairs: at, direction, length, width (default 1), vertical up|down, block (a *_stairs block),
          carve (default true going down: clears headroom). Stair facing is set automatically.
        - roof: at, size [w,1,d] footprint, block (a *_stairs block), direction (forward/back: ridge runs
          forward; left/right: ridge runs across), optional "ridge" block, optional "ends" block to fill the
          gable triangles. Height is set automatically.
        - door: at (lower half), direction the door faces, block (default oak_door). Two blocks tall.
        - blocks: at, blocks [{at, block}] for a few small details only. Never list blocks a box could make.
        - on: id of an earlier component. The origin becomes that component's left-near corner one block
          above its top, so stacked parts move together when resized (a roof on walls).
        - Later components overwrite earlier ones at the same spot: put doors and windows after their walls.

        BLOCKS: vanilla ids ("minecraft:" optional), with optional state in vanilla syntax, e.g.
        "oak_log[axis=forward]", "lantern[hanging=true]". Directions and axes in states may use
        forward/back/left/right/up. Only add states you are sure exist.

        RULES:
        - Return ONLY the JSON object. Keep plans small: usually 1-10 components.
        - Every component needs "block" and "length". For "blocks" components put the main block in
          "block"; "length" only matters for stairs (number of steps), use 1 elsewhere.
        - When the request gives no dimensions, use the whole selection: a platform or floor in a
          [w,h,d] selection is size [w,1,d]; clearing it is size [w,h,d] with block "air".
        - Stay inside the selection unless the request clearly needs more.
        - Preserve existing terrain and structures unless asked to clear, carve, flatten, or replace.
        - If CURRENT PLAN is given, the user is revising it: return the complete updated component list,
          change only what they asked for, and keep ids stable.
        - If the message is not a build request (greeting, question, unclear), return "nodes": [] and
          answer or ask a short clarifying question in "message".
        - title: 2-4 words. message: one short sentence saying what you planned.
        - suggestedMove: only when the user asks to be moved; local coordinates like everything else.

        EXAMPLE (selection [7,6,9], ground at y=0):
        {"title":"Spruce cabin","message":"Small spruce cabin with a door facing you and a gable roof.","nodes":[
         {"id":"floor","type":"box","at":[0,0,0],"size":[7,1,9],"block":"cobblestone"},
         {"id":"walls","type":"box","on":"floor","size":[7,3,9],"fill":"walls","block":"spruce_planks"},
         {"id":"door","type":"door","at":[3,1,0],"direction":"back"},
         {"id":"window","type":"box","at":[0,2,4],"size":[1,1,1],"block":"glass_pane"},
         {"id":"roof","type":"roof","on":"walls","at":[-1,0,-1],"size":[9,1,11],"block":"spruce_stairs","ends":"spruce_planks"}]}

        EXAMPLE (dig down; selection [2,30,20], ground surface at y=29):
        {"title":"Stairs down","message":"Stone brick staircase going 6 blocks down.","nodes":[
         {"id":"stairs","type":"stairs","at":[0,29,2],"direction":"forward","vertical":"down","length":6,"width":2,"block":"stone_brick_stairs"}]}
        Stairs start at the top step: going down, "at" is on the ground surface.
        """;

    public record Outcome(BuildPlan plan, ResolvedPlan resolved, Frame frame) {}

    private record Attempt(Outcome outcome, String error) {}

    private AiPlanner() {}

    public static CompletableFuture<Outcome> plan(Minecraft mc, String userPrompt) {
        Optional<ResolvedPlan> current = GhostPreviewManager.get().plan();
        // Revisions keep the original frame so the preview never shifts under the user.
        Optional<Frame> frame = current.map(ResolvedPlan::frame).or(() -> Frame.current(mc));
        if (frame.isEmpty()) return CompletableFuture.failedFuture(new PlanException("Look at a block or select an area with J first"));

        StringBuilder content = new StringBuilder("REQUEST: ").append(userPrompt).append('\n');
        List<String> earlier = AiConversation.get().turns().stream().filter(t -> "user".equals(t.role())).map(ChatMessage::content).toList();
        if (!earlier.isEmpty()) content.append("\nEARLIER REQUESTS IN THIS OPERATION:\n- ").append(String.join("\n- ", earlier)).append('\n');
        current.ifPresent(plan -> content.append("\nCURRENT PLAN:\n").append(PlanJson.toJson(plan.plan())).append('\n'));
        content.append("\nWORLD CONTEXT:\n").append(WorldContextService.capture(mc, frame.get()));

        List<ChatMessage> messages = List.of(new ChatMessage("system", SYSTEM_PROMPT), new ChatMessage("user", content.toString()));
        VoxelPilot.LOGGER.info("VoxelPilot: planning request\n{}", content);

        return attempt(mc, messages, frame.get(), REPAIR_ATTEMPTS).thenApply(outcome -> {
            AiConversation.get().addUserTurn(userPrompt);
            AiConversation.get().addAssistantTurn(PlanJson.toJson(outcome.plan()));
            return outcome;
        });
    }

    private static CompletableFuture<Outcome> attempt(Minecraft mc, List<ChatMessage> messages, Frame frame, int repairsLeft) {
        return BridgeServer.get().plan(messages)
            .thenCompose(plan -> mc.submit(() -> resolve(mc, plan, frame)).thenCompose(result -> {
                if (result.error() == null) return CompletableFuture.completedFuture(result.outcome());
                if (repairsLeft <= 0) return CompletableFuture.failedFuture(new PlanException(result.error()));
                VoxelPilot.LOGGER.warn("VoxelPilot: plan rejected, asking model to repair: {}", result.error());
                List<ChatMessage> retry = new ArrayList<>(messages);
                retry.add(new ChatMessage("assistant", PlanJson.toJson(plan)));
                retry.add(new ChatMessage("user", "The mod rejected that plan: " + result.error() + "\nReturn the complete corrected plan JSON."));
                return attempt(mc, retry, frame, repairsLeft - 1);
            }));
    }

    // Client thread: reads the client's copy of the world.
    private static Attempt resolve(Minecraft mc, BuildPlan plan, Frame frame) {
        if (plan.nodes.isEmpty()) return new Attempt(new Outcome(plan, null, frame), null);
        if (mc.level == null) throw new IllegalStateException("World not available");
        try {
            ResolvedPlan resolved = PlanResolver.resolve(mc.level, plan, frame, SelectionManager.get().box());
            return new Attempt(new Outcome(plan, resolved, frame), null);
        } catch (PlanException e) {
            return new Attempt(null, e.getMessage());
        }
    }
}
