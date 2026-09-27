package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.awareness.AwarenessManager;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Small immutable context captured on the Minecraft thread before AI work begins.
 * This is intentionally not a world dump. Minecraft remains the source of truth;
 * richer facts should be requested through bounded tools.
 */
public record GameContext(
    String dimension,
    BlockPos playerPosition,
    LookTarget lookingAt,
    String selectedItem,
    String biome,
    String awarenessPhase,
    boolean hasSelection,
    boolean hasPreview
) {
    public static GameContext capture(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return new GameContext("unknown", BlockPos.ZERO, null, "", "", "", false, false);
        }

        LookTarget look = null;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            var state = mc.level.getBlockState(pos);
            ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
            look = new LookTarget(id == null ? "unknown" : id.toString(), pos.immutable(), state.toString());
        }

        String phase = AwarenessManager.get().snapshot()
            .map(s -> s.phase().name())
            .orElse("");

        ResourceLocation dimension = mc.level.dimension().location();
        return new GameContext(
            dimension.toString(),
            mc.player.blockPosition().immutable(),
            look,
            mc.player.getMainHandItem().getHoverName().getString(),
            mc.level.getBiome(mc.player.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse("unknown"),
            phase,
            SelectionManager.get().box().isPresent(),
            GhostPreviewManager.get().hasPreview()
        );
    }

    public String promptSummary() {
        StringBuilder out = new StringBuilder();
        out.append("dimension=").append(dimension)
            .append(", player=").append(format(playerPosition));
        if (lookingAt != null) {
            out.append(", looking_at=").append(lookingAt.blockId())
                .append("@").append(format(lookingAt.position()));
        }
        if (!selectedItem.isBlank()) out.append(", held=").append(selectedItem);
        if (!biome.isBlank()) out.append(", biome=").append(biome);
        if (!awarenessPhase.isBlank()) out.append(", awareness=").append(awarenessPhase);
        if (hasSelection) out.append(", selection=active");
        if (hasPreview) out.append(", preview=active");
        return out.toString();
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "/" + pos.getY() + "/" + pos.getZ();
    }

    public record LookTarget(String blockId, BlockPos position, String blockState) {}
}
