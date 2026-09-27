package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import net.minecraft.client.Minecraft;

import java.util.List;

import java.util.concurrent.CompletableFuture;

/**
 * General Cmd-K AI entry point.
 *
 * Normal text goes to AI. Explicit slash commands are handled before this service.
 * AiPlanner remains the current AI implementation while the bounded tool loop is built;
 * world-changing results still become validated previews rather than executing directly.
 */
public final class AiService {
    private AiService() {}

    public static CompletableFuture<AiResult> run(Minecraft mc, String request) {
        GameContext context = GameContext.capture(mc);
        List<ReferenceResolver.ResolvedReference> references = ReferenceResolver.resolveAll(mc, request);

        return AiPlanner.plan(mc, request, context, references).thenApply(outcome -> {
            if (outcome.resolved() == null && outcome.plan().nodes.isEmpty()
                && outcome.plan().suggestedMove == null) {
                String message = outcome.plan().message;
                return new AiResult.Answer(message == null || message.isBlank() ? "No changes needed" : message);
            }
            return new AiResult.Build(outcome.plan(), outcome.resolved(), outcome.frame(), outcome.skipped());
        });
    }
}
