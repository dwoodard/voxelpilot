package dev.dwoodard.voxelpilot.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.ResolvedChange;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Draws each planned block as its real model, see-through, with the exact state it will
// have: a hopper's spout, a stair's slope, a door's hinge. Depth-tested like real blocks;
// the x-ray outlines drawn afterwards still mark anything hidden behind terrain.
final class GhostRenderer {
    private static final float ALPHA = 0.55F;
    private static final int MAX_BLOCKS = 8_000;
    private static final double MAX_DISTANCE_SQ = 96 * 96;
    private static final Direction[] FACES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    private static final RenderType TYPE = Sheets.translucentCullBlockSheet();

    // Positions of opaque ghost blocks, rebuilt when the preview changes, so faces buried
    // between two ghost blocks aren't drawn (otherwise a see-through wall shows every seam).
    private static List<ResolvedChange> cachedFor;
    private static final Set<Long> opaque = new HashSet<>();

    private GhostRenderer() {}

    static void render(PoseStack pose, Vec3 camera, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        List<ResolvedChange> changes = GhostPreviewManager.get().changes();
        if (changes.isEmpty() || mc.level == null) return;
        if (changes != cachedFor) {
            opaque.clear();
            for (ResolvedChange c : changes) if (!c.removal() && c.state().canOcclude()) opaque.add(c.pos().asLong());
            cachedFor = changes;
        }

        VertexConsumer consumer = buffers.getBuffer(TYPE);
        RandomSource random = RandomSource.create();
        int drawn = 0;
        for (ResolvedChange change : changes) {
            if (change.removal()) continue;
            BlockState state = change.state();
            // Chests, signs, etc. draw through block-entity renderers and water through the
            // fluid renderer; those keep just their outline for now.
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            BlockPos pos = change.pos();
            double dx = pos.getX() + 0.5 - camera.x, dy = pos.getY() + 0.5 - camera.y, dz = pos.getZ() + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE_SQ) continue;
            if (++drawn > MAX_BLOCKS) break;

            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            pose.pushPose();
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            // A hair larger than a block so a ghost replacing an existing block doesn't z-fight it.
            pose.translate(0.5, 0.5, 0.5);
            pose.scale(1.002F, 1.002F, 1.002F);
            pose.translate(-0.5, -0.5, -0.5);
            PoseStack.Pose last = pose.last();
            long seed = state.getSeed(pos);
            for (Direction face : FACES) {
                if (face != null && opaque.contains(pos.relative(face).asLong())) continue;
                random.setSeed(seed);
                for (BakedQuad quad : model.getQuads(state, face, random, ModelData.EMPTY, null)) {
                    float r = 1, g = 1, b = 1;
                    if (quad.isTinted()) {
                        int color = mc.getBlockColors().getColor(state, mc.level, pos, quad.getTintIndex());
                        if (color != -1) {
                            r = ((color >> 16) & 255) / 255F;
                            g = ((color >> 8) & 255) / 255F;
                            b = (color & 255) / 255F;
                        }
                    }
                    consumer.putBulkData(last, quad, r, g, b, ALPHA, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
                }
            }
            pose.popPose();
        }
        buffers.endBatch(TYPE);
    }
}
