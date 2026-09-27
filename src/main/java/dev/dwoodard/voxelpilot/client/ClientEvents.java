package dev.dwoodard.voxelpilot.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.PreviewMover;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.ui.CommandPaletteScreen;
import dev.dwoodard.voxelpilot.ui.InspectorScreen;
import dev.dwoodard.voxelpilot.ui.SettingsScreen;
import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    // Whether the Inspector should be showing. Cmd+Shift+K flips this. Opening the
    // palette (Cmd+K) or Settings (Cmd+,) temporarily occupies the one Minecraft Screen
    // slot on top of it; their onClose() hands control back to the Inspector if this is true.
    public static boolean inspectorOpen;

    // Agents (MCP, scripts) can connect as soon as a world is open, not only after Cmd+K.
    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        BridgeServer.get().ensureRunning();
    }

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
                inspectorOpen = !inspectorOpen;
                if (!(mc.screen instanceof CommandPaletteScreen) && !(mc.screen instanceof SettingsScreen)) {
                    mc.setScreen(inspectorOpen ? new InspectorScreen() : null);
                }
            } else if (mc.screen instanceof CommandPaletteScreen palette) {
                palette.onClose();
            } else {
                BridgeServer.get().ensureRunning();
                mc.setScreen(new CommandPaletteScreen());
            }
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS && command && key == GLFW.GLFW_KEY_COMMA) {
            if (mc.screen instanceof SettingsScreen settings) {
                settings.onClose();
            } else {
                mc.setScreen(new SettingsScreen());
            }
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS && command && shift
            && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            if (mc.screen == null) {
                BuildExecutor.get().confirm(mc).thenAcceptAsync(result -> {
                    if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] " + result.message()), true);
                }, mc);
            }
            return;
        }

        if (mc.screen != null) return;

        // Cmd = "the preview" (plain arrows = the selection): slide it, raise/lower it, turn it.
        if (command && GhostPreviewManager.get().hasPreview()) {
            String moved = null;
            if (alt && key == GLFW.GLFW_KEY_UP) moved = PreviewMover.move(mc, 0, 1, 0);
            else if (alt && key == GLFW.GLFW_KEY_DOWN) moved = PreviewMover.move(mc, 0, -1, 0);
            else if (key == GLFW.GLFW_KEY_UP) moved = PreviewMover.move(mc, 0, 0, 1);
            else if (key == GLFW.GLFW_KEY_DOWN) moved = PreviewMover.move(mc, 0, 0, -1);
            else if (key == GLFW.GLFW_KEY_LEFT) moved = PreviewMover.move(mc, -1, 0, 0);
            else if (key == GLFW.GLFW_KEY_RIGHT) moved = PreviewMover.move(mc, 1, 0, 0);
            else if (key == GLFW.GLFW_KEY_RIGHT_BRACKET && event.getAction() == GLFW.GLFW_PRESS) moved = PreviewMover.rotate(mc, 1);
            else if (key == GLFW.GLFW_KEY_LEFT_BRACKET && event.getAction() == GLFW.GLFW_PRESS) moved = PreviewMover.rotate(mc, -1);
            else if (key == GLFW.GLFW_KEY_BACKSPACE && event.getAction() == GLFW.GLFW_PRESS) {
                GhostPreviewManager.get().clear();
                moved = "Preview cleared";
            }
            if (moved != null) {
                if (mc.player != null) mc.player.displayClientMessage(Component.literal("[VoxelPilot] " + moved), true);
                return;
            }
        }

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

    private static void renderWayfinderMarker(PoseStack pose, VertexConsumer lines, Vec3 center, double radius) {
        AABB marker = new AABB(center.x - radius, center.y - radius, center.z - radius,
            center.x + radius, center.y + radius, center.z + radius);
        LevelRenderer.renderLineBox(pose, lines, marker, 0.25F, 1.0F, 0.35F, 0.86F);
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
        GhostRenderer.render(pose, camera, buffers);
        // Custom no-depth-test line type: calling RenderSystem.disableDepthTest() around
        // RenderType.lines() doesn't work, because that type re-enables depth testing when
        // its batch flushes, so terrain hid the selection.
        VertexConsumer lines = buffers.getBuffer(XrayRenderType.LINES);

        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        SelectionManager.get().box().ifPresent(box ->
            LevelRenderer.renderLineBox(pose, lines, box.aabb(), 0.95F, 0.75F, 0.15F, 1.0F));

        WayfinderManager.get().active().ifPresent(target -> {
            if (mc.player == null) return;
            Vec3 from = mc.player.position().add(0, 0.25, 0);
            Vec3 approach = Vec3.atCenterOf(target.approach());
            Vec3 horizontal = new Vec3(approach.x - from.x, 0, approach.z - from.z);
            double distance = horizontal.length();

            // Aircraft-style approach corridor: paired runway lights narrow toward the
            // surface arrival point. This communicates "go there" without pretending V1
            // knows a safe walkable path through every obstacle.
            if (distance > 1.0) {
                Vec3 forward = horizontal.normalize();
                Vec3 right = new Vec3(-forward.z, 0, forward.x);
                int gates = Math.min(12, Math.max(3, (int) Math.ceil(distance / 12.0)));
                for (int i = 1; i <= gates; i++) {
                    double t = (double) i / gates;
                    Vec3 center = from.add(horizontal.scale(t));
                    double halfWidth = 3.5 * (1.0 - t) + 0.65;
                    double y = from.y + (approach.y - from.y) * t + 0.15;
                    Vec3 left = new Vec3(center.x, y, center.z).add(right.scale(halfWidth));
                    Vec3 rightMarker = new Vec3(center.x, y, center.z).subtract(right.scale(halfWidth));
                    renderWayfinderMarker(pose, lines, left, 0.22);
                    renderWayfinderMarker(pose, lines, rightMarker, 0.22);
                }
            }

            // Surface approach marker and a vertical shaft line of sparse markers make
            // underground depth obvious while preserving the exact x-ray target outline.
            LevelRenderer.renderLineBox(pose, lines, new AABB(target.approach()).inflate(0.12),
                0.25F, 1.0F, 0.35F, 1.0F);
            int top = target.approach().getY();
            int bottom = target.pos().getY();
            if (top > bottom) {
                for (int y = top - 3; y > bottom; y -= 4) {
                    renderWayfinderMarker(pose, lines,
                        new Vec3(target.pos().getX() + 0.5, y + 0.5, target.pos().getZ() + 0.5), 0.16);
                }
            }
            LevelRenderer.renderLineBox(pose, lines, new AABB(target.pos()).inflate(0.04),
                0.25F, 1.0F, 0.35F, 1.0F);
        });

        var changes = GhostPreviewManager.get().changes();
        int stride = changes.size() > 5000 ? Math.max(1, changes.size() / 5000) : 1;
        for (int i = 0; i < changes.size(); i += stride) {
            var change = changes.get(i);
            boolean removal = change.removal();
            AABB box = new AABB(change.pos()).inflate(0.01);
            if (removal) LevelRenderer.renderLineBox(pose, lines, box, 0.95F, 0.25F, 0.25F, 0.72F);
            else LevelRenderer.renderLineBox(pose, lines, box, 0.25F, 0.80F, 0.95F, 0.72F);
        }

        buffers.endBatch(XrayRenderType.LINES);
        pose.popPose();
    }
}
