package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.ai.AiConversation;

import java.util.List;
import java.util.Optional;

// Holds the one current preview. While the model is still streaming, the preview is a
// draft that grows line by line and can't be confirmed; when the stream ends it becomes a
// numbered revision. The frame is kept across revisions so the preview never rotates or
// shifts under the user.
public final class GhostPreviewManager {
    private static final GhostPreviewManager INSTANCE = new GhostPreviewManager();
    private ResolvedPlan plan;
    private boolean drafting;
    private int revision;

    private GhostPreviewManager() {}
    public static GhostPreviewManager get() { return INSTANCE; }

    public synchronized void showDraft(ResolvedPlan draft) {
        plan = draft;
        drafting = true;
    }

    public synchronized void setPlan(ResolvedPlan plan) {
        this.plan = plan;
        drafting = false;
        revision++;
    }

    // A failed or empty stream puts back whatever was there before it started.
    public synchronized void restore(ResolvedPlan previous) {
        plan = previous;
        drafting = false;
    }

    // Clearing the preview is the "operation" boundary (confirm, cancel, or "clear
    // selection" all route here) - so this is where AI conversation history resets too.
    public synchronized void clear() {
        plan = null;
        drafting = false;
        revision = 0;
        AiConversation.get().reset();
    }

    public synchronized Optional<ResolvedPlan> plan() { return Optional.ofNullable(plan); }
    public synchronized Optional<Frame> frame() { return plan().map(ResolvedPlan::frame); }
    public synchronized List<ResolvedChange> changes() { return plan == null ? List.of() : plan.changes(); }
    public synchronized boolean hasPreview() { return plan != null && !plan.changes().isEmpty(); }
    public synchronized boolean drafting() { return drafting; }
    public synchronized int revision() { return revision; }
}
