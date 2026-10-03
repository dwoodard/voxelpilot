package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.build.Frame;
import dev.dwoodard.voxelpilot.build.ResolvedPlan;

import java.util.List;

/**
 * General AI result. Build planning is one possible result rather than the contract
 * for every Cmd-K request.
 */
public sealed interface AiResult permits AiResult.Answer, AiResult.Build {
    record Answer(String message) implements AiResult {}

    record Build(
        BuildPlan plan,
        ResolvedPlan resolved,
        Frame frame,
        List<String> skipped
    ) implements AiResult {}
}
