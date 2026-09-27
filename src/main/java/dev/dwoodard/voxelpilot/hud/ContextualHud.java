package dev.dwoodard.voxelpilot.hud;

import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Contextual pilot HUD. Cmd-K establishes intent; this surface reports only the
 * information needed while that activity is active.
 */
public final class ContextualHud {
    @SubscribeEvent
    public void onRenderGui(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        WayfinderManager.get().active().ifPresent(target ->
            renderWayfinder(event.getGuiGraphics(), mc, target));
    }

    private static void renderWayfinder(GuiGraphics gui, Minecraft mc, WayfinderManager.Target target) {
        Vec3 player = mc.player.position();
        Vec3 targetCenter = Vec3.atCenterOf(target.pos());
        double dx = targetCenter.x - player.x;
        double dz = targetCenter.z - player.z;
        double range = Math.sqrt(dx * dx + dz * dz);
        int vertical = target.pos().getY() - mc.player.blockPosition().getY();

        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Mth.wrapDegrees(bearing - mc.player.getYRot());
        String cue = directionCue(relative);

        String title = "WAYFINDER  //  TRACKING";
        String targetLine = target.name().toUpperCase();
        String rangeLine = "RANGE " + Math.round(range) + "m  //  " + verticalLabel(vertical);

        int margin = 12;
        int bottom = gui.guiHeight() - 42;
        gui.fill(margin - 5, bottom - 5, margin + 178, bottom + 31, 0x88000000);
        gui.drawString(mc.font, title, margin, bottom, 0xFF70FF8A, false);
        gui.drawString(mc.font, targetLine, margin, bottom + 11, 0xFFFFFFFF, false);
        gui.drawString(mc.font, rangeLine, margin, bottom + 22, 0xFFB8C0C8, false);

        // A compact edge cue keeps the target findable even when the world-space
        // designation is behind the camera. Near-center headings collapse to ACQUIRED.
        String bearingLine = cue + "  " + Math.round(Math.abs(relative)) + "\u00b0";
        int width = mc.font.width(bearingLine);
        int x = (gui.guiWidth() - width) / 2;
        gui.fill(x - 7, 8, x + width + 7, 24, 0x88000000);
        gui.drawString(mc.font, bearingLine, x, 12, 0xFF70FF8A, false);
    }

    private static String directionCue(double relative) {
        if (Math.abs(relative) <= 8) return "◆ ACQUIRED";
        return relative < 0 ? "◀ TARGET" : "TARGET ▶";
    }

    private static String verticalLabel(int vertical) {
        if (vertical == 0) return "LEVEL";
        return (vertical < 0 ? "BELOW " : "ABOVE ") + Math.abs(vertical) + "m";
    }
}
