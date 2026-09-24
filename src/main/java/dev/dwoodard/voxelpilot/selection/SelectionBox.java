package dev.dwoodard.voxelpilot.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public record SelectionBox(BlockPos min, BlockPos max) {
    public int width() { return max.getX() - min.getX() + 1; }
    public int height() { return max.getY() - min.getY() + 1; }
    public int depth() { return max.getZ() - min.getZ() + 1; }
    public long volume() { return (long) width() * height() * depth(); }
    public AABB aabb() { return new AABB(min, max.offset(1, 1, 1)); }
}
