package dev.dwoodard.voxelpilot.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Expands a component list into exact local block changes. Pure and deterministic: the
// same nodes always produce the same changes, which is what makes "confirm executes exactly
// the preview" hold. Later nodes overwrite earlier ones at the same position, so a door or
// window placed after a wall cuts into it.
public final class PlanRenderer {
    public static final int MAX_NODES = 64;
    public static final int MAX_CHANGES = 50_000;

    public record Result(List<LocalChange> changes, Map<String, Bounds> nodeBounds) {}

    private PlanRenderer() {}

    public static Result render(List<PlanNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return new Result(List.of(), Map.of());
        if (nodes.size() > MAX_NODES) throw new PlanException("Plan has more than " + MAX_NODES + " components");

        Map<String, Bounds> bounds = new LinkedHashMap<>();
        Map<Long, LocalChange> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            PlanNode node = nodes.get(i);
            String label = node.id == null || node.id.isBlank() ? "#" + (i + 1) : node.id.trim();
            try {
                if (bounds.containsKey(label)) throw new PlanException("duplicate id");
                int[] origin = origin(node, bounds);
                List<LocalChange> placed = new ArrayList<>();
                for (LocalChange c : Shapes.render(node)) {
                    placed.add(new LocalChange(c.x() + origin[0], c.y() + origin[1], c.z() + origin[2], c.block()));
                }
                bounds.put(label, Bounds.of(placed));
                for (LocalChange c : placed) {
                    out.put(key(c.x(), c.y(), c.z()), c);
                    if (out.size() > MAX_CHANGES) throw new PlanException("plan exceeds " + MAX_CHANGES + " blocks");
                }
            } catch (PlanException e) {
                throw new PlanException("Component '" + label + "' (" + node.type + "): " + e.getMessage());
            }
        }
        return new Result(List.copyOf(out.values()), Collections.unmodifiableMap(bounds));
    }

    // "on" stacks a component on an earlier one: its origin becomes that component's
    // left-near corner, one block above its top. This is what lets "make the walls taller"
    // carry the roof up with them.
    private static int[] origin(PlanNode node, Map<String, Bounds> bounds) {
        int[] at = node.at == null ? new int[]{0, 0, 0} : Shapes.triple(node.at, "at");
        if (node.on == null || node.on.isBlank()) return at;
        Bounds parent = bounds.get(node.on.trim());
        if (parent == null) throw new PlanException("\"on\" refers to '" + node.on + "', which is not an earlier component");
        return new int[]{parent.minX() + at[0], parent.maxY() + 1 + at[1], parent.minZ() + at[2]};
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
