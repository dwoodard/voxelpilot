package dev.dwoodard.voxelpilot.plan;

import java.util.ArrayList;
import java.util.List;

// Rasterized geometric primitives: the model gives numbers, this decides which blocks.
// Circles follow the usual block-circle conventions (radius measured to cell centers,
// +0.5 so small circles aren't diamonds; "even" puts the center between blocks).
final class Primitives {
    private Primitives() {}

    // Centered on the node origin. Ring by default, disc when filled; height extrudes it
    // into a round wall or solid cylinder. Arc angles: 0 = forward, 90 = right, clockwise.
    static List<LocalChange> circle(PlanNode node) {
        int r = Shapes.range(node.radius, "radius", 1, Shapes.MAX_RADIUS);
        int rz = node.radiusZ == null ? r : Shapes.range(node.radiusZ, "oval radius", 1, Shapes.MAX_RADIUS);
        int thickness = node.thickness == null ? 1 : Shapes.range(node.thickness, "thickness", 1, Shapes.MAX_RADIUS);
        int height = node.height == null ? 1 : Shapes.range(node.height, "height", 1, Shapes.MAX_DIM);
        boolean even = Boolean.TRUE.equals(node.even);
        boolean filled = Boolean.TRUE.equals(node.filled) || node.fillBlock != null;
        BlockSpec rim = Shapes.required(node.block, "block");
        BlockSpec inner = node.fillBlock != null ? BlockSpec.parse(node.fillBlock) : rim;
        double squareness = node.squareness == null ? 0 : Math.max(0, Math.min(1, node.squareness));
        // Superellipse exponent: 2 is a circle, large values approach a square.
        double exponent = 2.0 / Math.max(0.05, 1 - squareness);
        Arc arc = Arc.of(node.arcStart, node.arcEnd);

        double outerX = even ? r : r + 0.5, outerZ = even ? rz : rz + 0.5;
        double innerX = outerX - thickness, innerZ = outerZ - thickness;
        List<LocalChange> out = new ArrayList<>();
        for (int dz = -rz; dz <= (even ? rz - 1 : rz); dz++) {
            for (int dx = -r; dx <= (even ? r - 1 : r); dx++) {
                double px = even ? dx + 0.5 : dx, pz = even ? dz + 0.5 : dz;
                if (!inside(px, pz, outerX, outerZ, exponent) || !arc.contains(px, pz)) continue;
                boolean onRim = innerX <= 0 || innerZ <= 0 || !inside(px, pz, innerX, innerZ, exponent);
                BlockSpec block = onRim ? rim : filled ? inner : null;
                if (block == null) continue;
                for (int y = 0; y < height; y++) out.add(new LocalChange(dx, y, dz, block));
            }
        }
        return out;
    }

    // Centered on the node origin; "hollow" leaves a one-block shell, dome keeps y >= 0.
    static List<LocalChange> sphere(PlanNode node) {
        int r = Shapes.range(node.radius, "radius", 1, Shapes.MAX_RADIUS);
        BlockSpec block = Shapes.required(node.block, "block");
        boolean hollow = "hollow".equals(node.fill);
        double outer = (r + 0.5) * (r + 0.5), inner = (r - 0.5) * (r - 0.5);
        List<LocalChange> out = new ArrayList<>();
        for (int dy = Boolean.TRUE.equals(node.dome) ? 0 : -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > outer || (hollow && d < inner)) continue;
                    out.add(new LocalChange(dx, dy, dz, block));
                }
            }
        }
        return out;
    }

    // Straight 3D line from "at" to "to" (both in the same coordinates), diagonals included.
    static List<LocalChange> line(PlanNode node) {
        int[] from = node.at == null ? new int[]{0, 0, 0} : Shapes.triple(node.at, "at");
        int[] to = Shapes.triple(node.to, "to");
        int dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps > 256) throw new PlanException("line is longer than 256 blocks");
        BlockSpec block = Shapes.required(node.block, "block");
        List<LocalChange> out = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            out.add(new LocalChange((int) Math.round(dx * t), (int) Math.round(dy * t), (int) Math.round(dz * t), block));
        }
        return out;
    }

    // Stepped pyramid on a W x D base, each layer one block in from every side.
    static List<LocalChange> pyramid(PlanNode node) {
        int[] s = Shapes.size(node);
        int w = s[0], d = s[2];
        BlockSpec block = Shapes.required(node.block, "block");
        boolean hollow = "hollow".equals(node.fill);
        List<LocalChange> out = new ArrayList<>();
        for (int i = 0; i <= (Math.min(w, d) - 1) / 2; i++) {
            int x0 = i, x1 = w - 1 - i, z0 = i, z1 = d - 1 - i;
            boolean top = x1 - x0 < 2 || z1 - z0 < 2;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!hollow || edge || top) out.add(new LocalChange(x, i, z, block));
                }
            }
        }
        return out;
    }

    private static boolean inside(double px, double pz, double rx, double rz, double exponent) {
        return Math.pow(Math.abs(px) / rx, exponent) + Math.pow(Math.abs(pz) / rz, exponent) <= 1.0;
    }

    private record Arc(double start, double end, boolean full) {
        static Arc of(Integer start, Integer end) {
            if (start == null && end == null) return new Arc(0, 360, true);
            double s = start == null ? 0 : start, e = end == null ? 360 : end;
            if (e - s >= 360 || s - e >= 360) return new Arc(0, 360, true);
            return new Arc(((s % 360) + 360) % 360, ((e % 360) + 360) % 360, false);
        }

        boolean contains(double px, double pz) {
            if (full) return true;
            double deg = Math.toDegrees(Math.atan2(px, pz));
            if (deg < 0) deg += 360;
            return start <= end ? deg >= start && deg <= end : deg >= start || deg <= end;
        }
    }
}
