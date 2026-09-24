package dev.dwoodard.voxelpilot.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UndoSnapshot {
    private final Map<BlockPos, Entry> entries = new LinkedHashMap<>();

    public void capture(ServerLevel level, BlockPos pos) {
        if (entries.containsKey(pos)) return;
        BlockState state = level.getBlockState(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        CompoundTag tag = entity == null ? null : entity.saveWithFullMetadata();
        entries.put(pos.immutable(), new Entry(state, tag));
    }

    public void restore(ServerLevel level) {
        entries.forEach((pos, entry) -> {
            level.setBlock(pos, entry.state, 3);
            if (entry.blockEntityTag != null) {
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity != null) {
                    entity.load(entry.blockEntityTag.copy());
                    entity.setChanged();
                }
            }
        });
    }

    public int size() { return entries.size(); }
    private record Entry(BlockState state, CompoundTag blockEntityTag) {}
}
