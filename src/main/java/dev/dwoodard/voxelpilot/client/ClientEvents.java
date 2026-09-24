package dev.dwoodard.voxelpilot.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.ui.CommandPaletteScreen;
import dev.dwoodard.voxelpilot.ui.WorkspaceScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    @SubscribeEvent
    public void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_REPEAT) return;
        Minecraft mc = Minecraft.getInstance();
        int key = event.getKey();
        int mods = event.getModifiers();
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
        boolean command = (mods & GLFW.GLFW_MOD_SUPER) != 0 || (mods & GLFW.GLFW_MOD_CONTROL) != 0;

        if (event.getAction() == GLFW.GLFW_PRESS && command && key == GLFW.GLFW_KEY_K) {
            if (shift) {
                mc.setScreen(mc.screen instanceof WorkspaceScreen ? null : new WorkspaceScreen());
            } else {
                BridgeServer.get().ensureRunning();
                mc.setScreen(new CommandPaletteScreen());
            }
            return;
        }

        if (mc.screen != null) return;

        if (event.getAction() == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_J) {
            if (shift) SelectionManager.get().clear(mc);
            else SelectionManager.get().selectCrosshair(mc);
            return;
        }

        if (SelectionManager.get().anchor().isEmpty()) return;

        if (alt && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            boolean topFace = key == GLFW.GLFW_KEY_UP;
            SelectionManager.get().nudgeVertical(topFace, shift);
            feedback(mc);
            return;
        }

        Direction relative = switch (key) {
            case GLFW.GLFW_KEY_UP -> Direction.NORTH;
            case GLFW.GLFW_KEY_DOWN -> Direction.SOUTH;
            case GLFW.GLFW_KEY_LEFT -> Direction.WEST;
            case GLFW.GLFW_KEY_RIGHT -> Direction.EAST;
            default -> null;
        };
        if (relative != null) {
            SelectionManager.get().nudgeHorizontal(relative, shift);
            feedback(mc);
        }
    }

    private static void feedback(Minecraft mc) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[VoxelPilot] Selection " + SelectionManager.get().dimensions()), true);
        }
    }

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.disableDepthTest();

        SelectionManager.get().box().ifPresent(box ->
            LevelRenderer.renderLineBox(pose, lines, box.aabb(), 0.95F, 0.75F, 0.15F, 1.0F));

        var changes = GhostPreviewManager.get().changes();
        int stride = changes.size() > 5000 ? Math.max(1, changes.size() / 5000) : 1;
        for (int i = 0; i < changes.size(); i += stride) {
            var change = changes.get(i);
            boolean removal = "minecraft:air".equals(change.blockId());
            AABB box = new AABB(change.pos()).inflate(0.01);
            if (removal) LevelRenderer.renderLineBox(pose, lines, box, 0.95F, 0.25F, 0.25F, 0.72F);
            else LevelRenderer.renderLineBox(pose, lines, box, 0.25F, 0.80F, 0.95F, 0.72F);
        }

        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
        pose.popPose();
    }
}
