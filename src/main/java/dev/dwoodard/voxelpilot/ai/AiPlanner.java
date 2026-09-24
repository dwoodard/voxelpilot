package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.world.WorldContextService;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

public final class AiPlanner {
    private static final int MAX_CHANGES = 100_000;

    private AiPlanner() {}

    public static CompletableFuture<BuildPlan> plan(Minecraft mc, String userPrompt) {
        String context = WorldContextService.capture(mc);
        String preview = GhostPreviewManager.get().plan()
            .map(p -> "Current preview: title=" + p.title + ", mode=" + p.mode + ", changes=" + p.changes.size())
            .orElse("No current preview.");

        String system = """
            You are VoxelPilot, an AI planning layer for Minecraft 1.20.1.
            You DO NOT directly edit the world. You return a deterministic preview plan that the local mod validates and shows as ghost blocks.

            Rules:
            - Return ONLY JSON. No markdown.
            - Coordinates in changes are RELATIVE to the selection minimum corner. If no selection exists, they are relative to the selected anchor/crosshair origin.
            - Use valid Minecraft block IDs such as minecraft:oak_planks.
            - To remove a block use minecraft:air.
            - Preserve existing terrain/structures unless the user explicitly asks to modify, clear, carve, flatten, replace, or otherwise alter them.
            - mode must be PRESERVE, MODIFY, or REPLACE.
            - speed must be slow, normal, fast, or instant. Choose a sensible default from scope and complexity.
            - Never assume confirmation. Every result is only a preview.
            - Prefer compact, practical builds and stay near the supplied selection. You may propose changes outside it only when clearly needed; the mod will surface the affected bounds.
            - Do not move the player unless the user asks. You may supply suggestedMove only when it improves safety or viewing.
            - Maximum block changes: %d.

            Schema:
            {
              "title":"short name",
              "message":"brief description",
              "mode":"PRESERVE|MODIFY|REPLACE",
              "speed":"slow|normal|fast|instant",
              "changes":[{"x":0,"y":0,"z":0,"block":"minecraft:stone"}],
              "suggestedMove":{"x":0,"y":0,"z":0,"reason":"optional"}
            }
            """.formatted(MAX_CHANGES);

        String fullPrompt = userPrompt + "\n\nWORLD CONTEXT:\n" + context + "\n\n" + preview;
        return BridgeServer.get().plan(system, fullPrompt).thenApply(AiPlanner::validate);
    }

    private static BuildPlan validate(BuildPlan plan) {
        if (plan.changes.size() > MAX_CHANGES) throw new IllegalArgumentException("Plan exceeds " + MAX_CHANGES + " block changes");
        for (BlockChange change : plan.changes) {
            if (change.block == null || change.block.isBlank() || !change.block.contains(":")) {
                throw new IllegalArgumentException("Invalid block id in plan");
            }
            if (Math.abs(change.x) > 2048 || Math.abs(change.y) > 512 || Math.abs(change.z) > 2048) {
                throw new IllegalArgumentException("Plan coordinate exceeds safety bounds");
            }
        }
        return plan;
    }
}
