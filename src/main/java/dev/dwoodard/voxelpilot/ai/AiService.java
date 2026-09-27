package dev.dwoodard.voxelpilot.ai;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * General Cmd-K AI entry point.
 *
 * V1 deliberately delegates to the existing planner so this refactor does not
 * destabilize build generation. The contract is now general, which lets later
 * slices add bounded tools and question answering without making BuildPlan the
 * universal AI response type.
 */
public final class AiService {
    private AiService() {}

    public static CompletableFuture<AiResult> run(Minecraft mc, String request) {
        GameContext context = GameContext.capture(mc);

        return AiPlanner.plan(mc, request, context).thenApply(outcome -> {
            if (outcome.resolved() == null && outcome.plan().nodes.isEmpty()
                && outcome.plan().suggestedMove == null) {
                String message = outcome.plan().message;
                return new AiResult.Answer(message == null || message.isBlank() ? "No changes needed" : message);
            }
            return new AiResult.Build(outcome.plan(), outcome.resolved(), outcome.frame(), outcome.skipped());
        });
    }
}
