package dev.dwoodard.voxelpilot.ai;

import java.util.ArrayList;
import java.util.List;

public final class BuildPlan {
    public String title = "AI Build";
    public String message = "";
    public String mode = "PRESERVE";
    public String speed = "normal";
    public List<BlockChange> changes = new ArrayList<>();
    public SuggestedMove suggestedMove;

    public static final class SuggestedMove {
        public double x;
        public double y;
        public double z;
        public String reason = "";
    }
}
