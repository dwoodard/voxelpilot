package dev.dwoodard.voxelpilot.wayfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class PathRenderer {
    private static final int PATH_COLOR = 0xFF00FF00;

    private PathRenderer() {}

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Minecraft mc) {
        var target = WayfinderManager.get().active();
        if (target.isEmpty() || target.get().path().isEmpty()) return;

        List<BlockPos> path = target.get().path();
        if (path.isEmpty()) return;

        if (mc.player == null) return;

        poseStack.pushPose();

        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        double camY = mc.gameRenderer.getMainCamera().getPosition().y;
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z;

        VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.LINES);

        int r = (PATH_COLOR >> 16) & 0xFF;
        int g = (PATH_COLOR >> 8) & 0xFF;
        int b = PATH_COLOR & 0xFF;
        int a = 255;

        double prevX = mc.player.getX() - camX;
        double prevY = mc.player.getY() - camY;
        double prevZ = mc.player.getZ() - camZ;

        for (BlockPos waypoint : path) {
            double x = waypoint.getX() + 0.5 - camX;
            double y = waypoint.getY() + 0.5 - camY;
            double z = waypoint.getZ() + 0.5 - camZ;

            lineBuffer.vertex(poseStack.last().pose(), (float) prevX, (float) prevY, (float) prevZ)
                .color(r, g, b, a)
                .normal(poseStack.last().normal(), 0, 1, 0)
                .endVertex();

            lineBuffer.vertex(poseStack.last().pose(), (float) x, (float) y, (float) z)
                .color(r, g, b, a)
                .normal(poseStack.last().normal(), 0, 1, 0)
                .endVertex();

            prevX = x;
            prevY = y;
            prevZ = z;
        }

        double finalX = target.get().pos().getX() + 0.5 - camX;
        double finalY = target.get().pos().getY() + 0.5 - camY;
        double finalZ = target.get().pos().getZ() + 0.5 - camZ;

        lineBuffer.vertex(poseStack.last().pose(), (float) prevX, (float) prevY, (float) prevZ)
            .color(r, g, b, a)
            .normal(poseStack.last().normal(), 0, 1, 0)
            .endVertex();

        lineBuffer.vertex(poseStack.last().pose(), (float) finalX, (float) finalY, (float) finalZ)
            .color(r, g, b, a)
            .normal(poseStack.last().normal(), 0, 1, 0)
            .endVertex();

        poseStack.popPose();
    }
}
