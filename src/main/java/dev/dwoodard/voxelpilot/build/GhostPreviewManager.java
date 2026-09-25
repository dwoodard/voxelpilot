package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.ai.AiConversation;

import java.util.List;
import java.util.Optional;

// Holds the one current preview. Each new plan in the same operation bumps the revision;
// the frame is kept across revisions so the preview never rotates or shifts under the user.
public final class GhostPreviewManager {
    private static final GhostPreviewManager INSTANCE = new GhostPreviewManager();
    private ResolvedPlan plan;
    private int revision;

    private GhostPreviewManager() {}
    public static GhostPreviewManager get() { return INSTANCE; }

    public synchronized void setPlan(ResolvedPlan plan) {
        this.plan = plan;
        revision++;
    }

    // Clearing the preview is the "operation" boundary (confirm, cancel, or "clear
    // selection" all route here) - so this is where AI conversation history resets too.
    public synchronized void clear() {
        plan = null;
        revision = 0;
        AiConversation.get().reset();
    }

    public synchronized Optional<ResolvedPlan> plan() { return Optional.ofNullable(plan); }
    public synchronized Optional<Frame> frame() { return plan().map(ResolvedPlan::frame); }
    public synchronized List<ResolvedChange> changes() { return plan == null ? List.of() : plan.changes(); }
    public synchronized boolean hasPreview() { return plan != null && !plan.changes().isEmpty(); }
    public synchronized int revision() { return revision; }
}
