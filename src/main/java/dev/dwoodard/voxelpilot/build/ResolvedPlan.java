package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.ai.BuildPlan;
import net.minecraft.core.BlockPos;

import java.util.List;

// An immutable, fully validated preview: the component list the model wrote, the frame it
// was resolved in, and the exact changes it expands to. Confirm executes `changes` and
// nothing else. The mode is computed from what the plan actually does to existing blocks,
// not reported by the model.
public record ResolvedPlan(
    BuildPlan plan,
    Frame frame,
    List<ResolvedChange> changes,
    int replacedExisting,
    int outsideSelection,
    BlockPos min,
    BlockPos max,
    List<String> notes
) {
    public String mode() {
        if (replacedExisting == 0) return "PRESERVE";
        return replacedExisting * 2 >= changes.size() ? "REPLACE" : "MODIFY";
    }
}
