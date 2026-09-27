package dev.dwoodard.voxelpilot.hud;

import dev.dwoodard.voxelpilot.awareness.AwarenessManager;
import dev.dwoodard.voxelpilot.awareness.AwarenessManager.AwarenessState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Presentation only. Awareness decides what matters; the HUD renders that state.
 */
public final class ContextualHud {
    @SubscribeEvent
    public void onRenderGui(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        AwarenessManager.get().current(mc).ifPresent(state ->
            render(event.getGuiGraphics(), mc, state));
    }

    private static void render(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        switch (state.level()) {
            case NAVIGATION -> renderNavigation(gui, mc, state);
            case APPROACH -> renderApproach(gui, mc, state);
            case PRECISION -> renderPrecision(gui, mc, state);
        }
    }

    private static void renderNavigation(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        renderBearing(gui, mc, state);
        renderPanel(gui, mc, "WAYFINDER  //  TRACKING", state.targetName().toUpperCase(),
            "RANGE " + Math.round(state.horizontalDistance()) + "m  //  " + verticalLabel(state.verticalDistance()));
    }

    private static void renderApproach(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        renderBearing(gui, mc, state);
        renderPanel(gui, mc, "WAYFINDER  //  APPROACH", state.targetName().toUpperCase(),
            "ENTRY " + Math.round(state.approachDistance()) + "m  //  TARGET " + verticalLabel(state.verticalDistance()));
    }

    private static void renderPrecision(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        var pos = state.targetPosition();
        renderPanel(gui, mc, "WAYFINDER  //  PRECISION", state.targetName().toUpperCase(),
            "XYZ " + pos.getX() + " / " + pos.getY() + " / " + pos.getZ());
    }

    private static void renderBearing(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        double relative = state.relativeBearing();
        String cue = Math.abs(relative) <= 8 ? "◆ ACQUIRED" : relative < 0 ? "◀ TARGET" : "TARGET ▶";
        String line = cue + "  " + Math.round(Math.abs(relative)) + "\u00b0";
        int width = mc.font.width(line);
        int x = (gui.guiWidth() - width) / 2;
        gui.fill(x - 7, 8, x + width + 7, 24, 0x88000000);
        gui.drawString(mc.font, line, x, 12, 0xFF70FF8A, false);
    }

    private static void renderPanel(GuiGraphics gui, Minecraft mc, String title, String target, String detail) {
        int margin = 12;
        int bottom = gui.guiHeight() - 42;
        gui.fill(margin - 5, bottom - 5, margin + 210, bottom + 31, 0x88000000);
        gui.drawString(mc.font, title, margin, bottom, 0xFF70FF8A, false);
        gui.drawString(mc.font, target, margin, bottom + 11, 0xFFFFFFFF, false);
        gui.drawString(mc.font, detail, margin, bottom + 22, 0xFFB8C0C8, false);
    }

    private static String verticalLabel(int vertical) {
        if (vertical == 0) return "LEVEL";
        return (vertical < 0 ? "BELOW " : "ABOVE ") + Math.abs(vertical) + "m";
    }
}
