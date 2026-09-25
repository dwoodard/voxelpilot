package dev.dwoodard.voxelpilot.plan;

import java.util.Locale;

// Horizontal directions in the plan's local frame: x = right, z = forward (away from the
// player at anchor time). Models reason far better in "forward/left" than in north/east,
// and the frame maps these onto world directions at resolve time.
public enum Dir {
    FORWARD(0, 1), BACK(0, -1), RIGHT(1, 0), LEFT(-1, 0);

    public final int dx;
    public final int dz;

    Dir(int dx, int dz) { this.dx = dx; this.dz = dz; }

    public Dir opposite() {
        return switch (this) { case FORWARD -> BACK; case BACK -> FORWARD; case RIGHT -> LEFT; case LEFT -> RIGHT; };
    }

    // Right-hand turn seen from above.
    public Dir clockwise() {
        return switch (this) { case FORWARD -> RIGHT; case RIGHT -> BACK; case BACK -> LEFT; case LEFT -> FORWARD; };
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static boolean isName(String value) {
        if (value == null) return false;
        for (Dir d : values()) if (d.key().equals(value)) return true;
        return false;
    }

    public static Dir parse(String value, Dir fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (Dir d : values()) if (d.key().equals(v)) return d;
        throw new PlanException("Unknown direction '" + value + "' (use forward, back, left, right)");
    }
}
