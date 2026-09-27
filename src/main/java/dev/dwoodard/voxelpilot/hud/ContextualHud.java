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
        if (tokens.isEmpty() || mc.player == null || mc.level == null) return;

        // Pins are awareness cues, not panels. The active Wayfinder target owns the rich
        // navigation UI; passive pins stay terse and live on the nearest screen edge.
        int top = 34;
        int bottom = gui.guiHeight() - 52;
        int leftY = top;
        int rightY = top;
        int topX = 12;
        int bottomX = 12;

        for (String token : tokens) {
            String name = token.startsWith("@") ? token.substring(1) : token;
            var resolved = ReferenceResolver.resolve(mc, name);
            if (resolved.isEmpty()) continue;
            var reference = resolved.get();

            // Unknown/stale spatial facts do not get a fake directional marker. Cmd-K is
            // still the place to inspect them.
            if (!reference.hasPosition()
                || !mc.level.dimension().location().toString().equals(reference.dimension())) continue;

            double dx = reference.x() - mc.player.getX();
            double dz = reference.z() - mc.player.getZ();
            double yaw = Math.toRadians(mc.player.getYRot());
            double localX = dx * Math.cos(yaw) + dz * Math.sin(yaw);
            double localZ = dz * Math.cos(yaw) - dx * Math.sin(yaw);

            String distance = reference.distance() == null ? "" : " " + Math.round(reference.distance()) + "m";
            String label = reference.name().toUpperCase() + distance;

            // Pick the dominant relative direction. This is intentionally discrete: it is
            // glanceable, stable, and much quieter than six continuously sliding widgets.
            if (Math.abs(localX) > Math.abs(localZ)) {
                if (localX < 0) {
                    String line = "◀ " + label;
                    gui.drawString(mc.font, line, 8, leftY, 0xFFB8C0C8, true);
                    leftY += 12;
                } else {
                    String line = label + " ▶";
                    int width = mc.font.width(line);
                    gui.drawString(mc.font, line, gui.guiWidth() - width - 8, rightY, 0xFFB8C0C8, true);
                    rightY += 12;
                }
            } else if (localZ < 0) {
                String line = "▲ " + label;
                gui.drawString(mc.font, line, topX, 8, 0xFFB8C0C8, true);
                topX += mc.font.width(line) + 14;
            } else {
                String line = "▼ " + label;
                gui.drawString(mc.font, line, bottomX, bottom, 0xFFB8C0C8, true);
                bottomX += mc.font.width(line) + 14;
            }
        }
    }

    private static String verticalLabel(int vertical) {
        if (vertical == 0) return "LEVEL";
        return (vertical < 0 ? "BELOW " : "ABOVE ") + Math.abs(vertical) + "m";
    }
}
