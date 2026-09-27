package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.VoxelPilot;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * General Cmd-K AI entry point.
 *
 * Questions are answered from a compact immutable game snapshot and do not require a
 * build frame. Build-capable requests continue through AiPlanner until the agent/tool
 * router replaces this transitional split.
 */
public final class AiService {
    private static final String QUESTION_PROMPT = """
        You are VoxelPilot inside Minecraft 1.20.1.
        Answer the player's question briefly using only facts in GAME and stable Minecraft knowledge.
        GAME facts are direct observations from the running client. Never invent nearby blocks,
        entities, routes, inventory contents, or world state that GAME does not provide.
        If the requested world fact is not present, say that you do not have enough observed
        information yet. Do not claim that something does not exist merely because it was not observed.
        """;

    private AiService() {}

    public static CompletableFuture<AiResult> run(Minecraft mc, String request) {
        GameContext context = GameContext.capture(mc);

        if (looksLikeQuestion(request)) {
            String content = "GAME: " + context.promptSummary() + "\nQUESTION: " + request;
            List<ChatMessage> messages = List.of(
                new ChatMessage("system", QUESTION_PROMPT),
                new ChatMessage("user", content)
            );
            VoxelPilot.LOGGER.info("VoxelPilot: question request\n{}", content);
            return BridgeServer.get().stream(messages, ignored -> {})
                .thenApply(answer -> new AiResult.Answer(answer.strip()));
        }

        return AiPlanner.plan(mc, request, context).thenApply(outcome ->
            new AiResult.Build(outcome.plan(), outcome.resolved(), outcome.frame(), outcome.skipped())
        );
    }

    /**
     * Transitional conservative router. Explicit questions bypass the build planner; ambiguous
     * imperatives still use the existing proven build path. This is intentionally small until
     * bounded tools give the agent a better routing contract.
     */
    private static boolean looksLikeQuestion(String request) {
        String lower = request.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("?")
            || lower.startsWith("what ")
            || lower.startsWith("what's ")
            || lower.startsWith("whats ")
            || lower.startsWith("where ")
            || lower.startsWith("where's ")
            || lower.startsWith("wheres ")
            || lower.startsWith("why ")
            || lower.startsWith("how ")
            || lower.startsWith("which ")
            || lower.startsWith("who ")
            || lower.startsWith("when ")
            || lower.startsWith("can i ")
            || lower.startsWith("could i ")
            || lower.startsWith("is ")
            || lower.startsWith("are ")
            || lower.startsWith("do ")
            || lower.startsWith("does ");
    }
}
