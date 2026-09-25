package dev.dwoodard.voxelpilot.plan;

// One block in the plan's local frame (x = right, y = up, z = forward).
public record LocalChange(int x, int y, int z, BlockSpec block) {}
