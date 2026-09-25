package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;

// One plan as the model wrote it: the accepted script lines (sent back verbatim for
// revisions) and the components they parse to. No nodes means a conversational reply or a
// move request.
public final class BuildPlan {
    public String title = "AI Build";
    public String message = "";
    public List<String> script = new ArrayList<>();
    public List<PlanNode> nodes = new ArrayList<>();
    public SuggestedMove suggestedMove;

    // Local coordinates, like everything else in a plan.
    public static final class SuggestedMove {
        public double x;
        public double y;
        public double z;
        public String reason = "";
    }
}
