package dev.dwoodard.voxelpilot.build;

public enum BuildSpeed {
    SLOW(1), NORMAL(8), FAST(40), INSTANT(10_000);

    public final int blocksPerTick;
    BuildSpeed(int blocksPerTick) { this.blocksPerTick = blocksPerTick; }

    public static BuildSpeed parse(String value) {
        if (value == null) return NORMAL;
        try { return valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return NORMAL; }
    }
}
