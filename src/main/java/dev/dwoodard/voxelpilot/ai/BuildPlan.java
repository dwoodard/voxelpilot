package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;

// What the model returns: a short list of components (see plan.Shapes), never a
// block-by-block list. An empty list means a conversational reply or a move request.
public final class BuildPlan {
    public String title = "AI Build";
    public String message = "";
    public List<PlanNode> nodes = new ArrayList<>();
    public SuggestedMove suggestedMove;

    public static final class SuggestedMove {
        public double x;
        public double y;
        public double z;
        public String reason = "";
    }
}
