package dev.dwoodard.voxelpilot.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

// One exact world change: what the ghost shows and what confirm places.
public record ResolvedChange(BlockPos pos, BlockState state) {
    public boolean removal() { return state.isAir(); }
}
