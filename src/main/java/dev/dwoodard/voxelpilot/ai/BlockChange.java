package dev.dwoodard.voxelpilot.ai;

public final class BlockChange {
    public int x;
    public int y;
    public int z;
    public String block;

    public BlockChange() {}

    public BlockChange(int x, int y, int z, String block) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
    }
}
