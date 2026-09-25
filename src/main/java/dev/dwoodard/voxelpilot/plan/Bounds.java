package dev.dwoodard.voxelpilot.plan;

import java.util.Collection;

public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Bounds of(Collection<LocalChange> changes) {
        if (changes.isEmpty()) throw new PlanException("produced no blocks");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (LocalChange c : changes) {
            minX = Math.min(minX, c.x()); minY = Math.min(minY, c.y()); minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x()); maxY = Math.max(maxY, c.y()); maxZ = Math.max(maxZ, c.z());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
