package dev.dwoodard.voxelpilot.build;

public enum BuildSpeed {
    SLOW(1, 1), NORMAL(8, 2), FAST(40, 4), INSTANT(10_000, 6);

    public final int blocksPerTick;
    // Remote servers rate-limit and anti-cheat fast placement, so the cap there is far lower.
    public final int remoteBlocksPerTick;
    BuildSpeed(int blocksPerTick, int remoteBlocksPerTick) {
        this.blocksPerTick = blocksPerTick;
        this.remoteBlocksPerTick = remoteBlocksPerTick;
    }

    public static BuildSpeed parse(String value) {
        if (value == null) return NORMAL;
        try { return valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return NORMAL; }
    }
}
