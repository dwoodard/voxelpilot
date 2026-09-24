package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.ai.BlockChange;
import dev.dwoodard.voxelpilot.ai.BuildPlan;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class GhostPreviewManager {
    private static final GhostPreviewManager INSTANCE = new GhostPreviewManager();
    private BuildPlan plan;
    private List<ResolvedChange> resolved = List.of();

    private GhostPreviewManager() {}
    public static GhostPreviewManager get() { return INSTANCE; }

    public synchronized void setPlan(BuildPlan plan) {
        BlockPos origin = SelectionManager.get().box().map(b -> b.min())
            .orElseGet(() -> SelectionManager.get().anchor().orElse(BlockPos.ZERO));
        List<ResolvedChange> changes = new ArrayList<>();
        for (BlockChange c : plan.changes) {
            changes.add(new ResolvedChange(origin.offset(c.x, c.y, c.z), c.block));
        }
        this.plan = plan;
        this.resolved = Collections.unmodifiableList(changes);
    }

    public synchronized void clear() { plan = null; resolved = List.of(); }
    public synchronized Optional<BuildPlan> plan() { return Optional.ofNullable(plan); }
    public synchronized List<ResolvedChange> changes() { return resolved; }
    public synchronized boolean hasPreview() { return plan != null && !resolved.isEmpty(); }

    public record ResolvedChange(BlockPos pos, String blockId) {}
}
