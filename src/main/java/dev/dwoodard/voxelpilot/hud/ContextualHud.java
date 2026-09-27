package dev.dwoodard.voxelpilot.hud;

import dev.dwoodard.voxelpilot.awareness.AwarenessManager;
import dev.dwoodard.voxelpilot.awareness.AwarenessManager.AwarenessState;
import dev.dwoodard.voxelpilot.reference.ReferencePins;
import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ContextualHud {
    @SubscribeEvent
    public void onRenderGui(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        AwarenessManager.get().snapshot().ifPresent(state -> render(event.getGuiGraphics(), mc, state));
        renderPinnedReferences(event.getGuiGraphics(), mc);
    }

    private static void render(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        switch (state.phase()) {
            case NAVIGATING -> renderNavigation(gui, mc, state);
            case APPROACHING_ENTRY -> renderApproach(gui, mc, state);
            case TARGETING -> renderPrecision(gui, mc, state);
        }
        renderHighestPriorityObservation(gui, mc, state);
    }

    private static void renderNavigation(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        renderBearing(gui, mc, state);
        renderPanel(gui, mc, "WAYFINDER  //  TRACKING", state.targetName().toUpperCase(),
            Math.round(state.horizontalDistance()) + "m  //  " + verticalLabel(state.verticalDistance()));
    }

    private static void renderApproach(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        renderBearing(gui, mc, state);
        renderPanel(gui, mc, "WAYFINDER  //  APPROACH", state.targetName().toUpperCase(),
            "SUGGESTED ENTRY " + Math.round(state.approachDistance()) + "m  //  TARGET " + verticalLabel(state.verticalDistance()));
    }

    private static void renderPrecision(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        var pos = state.targetPosition();
        String coordinates = "XYZ " + pos.getX() + " / " + pos.getY() + " / " + pos.getZ();
        int width = Math.max(mc.font.width(state.targetName().toUpperCase()), mc.font.width(coordinates));
        int x = (gui.guiWidth() - width) / 2;
        int y = Math.max(34, gui.guiHeight() / 2 - 38);
        gui.fill(x - 9, y - 7, x + width + 9, y + 27, 0x88000000);
        gui.drawCenteredString(mc.font, "◆  " + state.targetName().toUpperCase(), gui.guiWidth() / 2, y, 0xFF70FF8A);
        gui.drawCenteredString(mc.font, coordinates, gui.guiWidth() / 2, y + 13, 0xFFFFFFFF);
    }

    private static void renderBearing(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        double relative = state.relativeBearing();
        String cue = Math.abs(relative) <= 8 ? "◆ ON BEARING" : relative < 0 ? "◀ TARGET" : "TARGET ▶";
        String line = cue + "  " + Math.round(Math.abs(relative)) + "\u00b0";
        int width = mc.font.width(line);
        int x = (gui.guiWidth() - width) / 2;
        gui.fill(x - 7, 8, x + width + 7, 24, 0x88000000);
        gui.drawString(mc.font, line, x, 12, 0xFF70FF8A, false);
    }

    private static void renderPanel(GuiGraphics gui, Minecraft mc, String title, String target, String detail) {
        int margin = 12;
        int bottom = gui.guiHeight() - 42;
        gui.fill(margin - 5, bottom - 5, margin + 230, bottom + 31, 0x88000000);
        gui.drawString(mc.font, title, margin, bottom, 0xFF70FF8A, false);
        gui.drawString(mc.font, target, margin, bottom + 11, 0xFFFFFFFF, false);
        gui.drawString(mc.font, detail, margin, bottom + 22, 0xFFB8C0C8, false);
    }

    private static void renderHighestPriorityObservation(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        if (state.observations().isEmpty()) return;
        var observation = state.observations().get(0);
        String line = observation.severity() + "  //  " + observation.message();
        int width = mc.font.width(line);
        int x = gui.guiWidth() - width - 12;
        int y = gui.guiHeight() - 20;
        gui.fill(x - 6, y - 4, gui.guiWidth() - 6, y + 12, 0x88000000);
        gui.drawString(mc.font, line, x, y, 0xFFFFD36A, false);
    }


    private static void renderPinnedReferences(GuiGraphics gui, Minecraft mc) {
        var tokens = ReferencePins.get().tokens();
        if (tokens.isEmpty()) return;

        int x = 12;
        int y = 12;
        int width = 190;
        int rowHeight = 12;
        int height = 16 + tokens.size() * rowHeight;
        gui.fill(x - 5, y - 5, x + width, y + height, 0x88000000);
        gui.drawString(mc.font, "PINNED", x, y, 0xFF70FF8A, false);

        int row = y + 14;
        for (String token : tokens) {
            String name = token.startsWith("@") ? token.substring(1) : token;
            var resolved = ReferenceResolver.resolve(mc, name);
            String line;
            if (resolved.isEmpty()) {
                line = token.toUpperCase() + "  UNKNOWN";
            } else {
                var reference = resolved.get();
                StringBuilder detail = new StringBuilder(reference.token().substring(1).toUpperCase());
                if (reference.distance() != null) {
                    detail.append("  ").append(Math.round(reference.distance())).append("m");
                    String bearing = reference.bearing();
                    if (!bearing.isBlank()) detail.append(" ").append(bearing);
                } else if (!reference.hasPosition()) {
                    detail.append("  POSITION UNKNOWN");
                } else if (mc.level != null
                    && !mc.level.dimension().location().toString().equals(reference.dimension())) {
                    detail.append("  ").append(reference.dimension());
                }
                if (reference.online()) detail.append("  ONLINE");
                line = detail.toString();
            }
            gui.drawString(mc.font, line, x, row, 0xFFE8EAED, false);
            row += rowHeight;
        }
    }

    private static String verticalLabel(int vertical) {
        if (vertical == 0) return "LEVEL";
        return (vertical < 0 ? "BELOW " : "ABOVE ") + Math.abs(vertical) + "m";
    }
}
