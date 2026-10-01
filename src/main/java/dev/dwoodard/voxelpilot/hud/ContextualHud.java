package dev.dwoodard.voxelpilot.hud;

import dev.dwoodard.voxelpilot.awareness.AwarenessManager;
import dev.dwoodard.voxelpilot.awareness.AwarenessManager.AwarenessState;
import dev.dwoodard.voxelpilot.reference.ReferencePins;
import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import dev.dwoodard.voxelpilot.util.RelativeBearing;
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
            case TARGETING -> renderBearing(gui, mc, state, true, false, true);
            case REACHED, TARGET_LOST -> renderBearing(gui, mc, state, true, true, false);
        }
        renderHighestPriorityObservation(gui, mc, state);
    }

    private static void renderNavigation(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        renderBearing(gui, mc, state, false, false, false);
    }

    private static void renderApproach(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        renderBearing(gui, mc, state, true, false, false);
    }

    private static void renderBearing(GuiGraphics gui, Minecraft mc, AwarenessState state, boolean approaching, boolean isReached, boolean isTargeting) {
        double relative = state.relativeBearing();
        String arrow = Math.abs(relative) <= 8 ? "◆" : relative < 0 ? "◀" : "▶";

        // Rotated into the player's own frame (not raw world x/z) so these arrows always
        // agree with the leading bearing arrow above: both are relative to facing.
        var pos = state.targetPosition();
        RelativeBearing.Local local = RelativeBearing.toLocal(mc, pos.getX() - mc.player.getX(), pos.getZ() - mc.player.getZ());
        int right = (int) Math.round(local.right());
        int forward = (int) Math.round(local.forward());
        int up = (int) Math.round(pos.getY() - mc.player.getY());

        String detail = RelativeBearing.deltaLabel(right, forward, up)
            // Always reserve space for status icon (no jumping)
            + (isReached ? "  ✔" : isTargeting ? "  ◉" : "   ");

        String line = arrow + " " + state.targetName().toUpperCase() + "  " + detail;
        int width = mc.font.width(line);
        // Fixed position (top right, with padding)
        int x = gui.guiWidth() - width - 10;
        int y = 10;

        gui.fill(x - 4, y - 2, x + width + 4, y + 12, 0x88000000);
        gui.drawString(mc.font, line, x, y, 0xFF70FF8A, false);

        renderBearingCrosshair(gui, mc, state);
    }

    private static void renderBearingCrosshair(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        double relative = state.relativeBearing();
        int centerX = gui.guiWidth() / 2;
        int centerY = gui.guiHeight() / 2;

        // Draw directional marker around center based on bearing
        int markerDistance = 24;
        if (Math.abs(relative) <= 22.5) {
            // Target straight ahead or nearly ahead
            drawDirectionalMarker(gui, centerX, centerY - markerDistance, "▲", 0xFF70FF8A);
        } else if (relative < -22.5 && relative >= -112.5) {
            // Target to the left
            drawDirectionalMarker(gui, centerX - markerDistance, centerY, "◀", 0xFF70FF8A);
        } else if (relative > 22.5 && relative <= 112.5) {
            // Target to the right
            drawDirectionalMarker(gui, centerX + markerDistance, centerY, "▶", 0xFF70FF8A);
        } else {
            // Target behind
            drawDirectionalMarker(gui, centerX, centerY + markerDistance, "▼", 0xFF70FF8A);
        }
    }

    private static void drawDirectionalMarker(GuiGraphics gui, int x, int y, String symbol, int color) {
        gui.drawCenteredString(Minecraft.getInstance().font, symbol, x, y - 4, color);
    }

    private static void renderHighestPriorityObservation(GuiGraphics gui, Minecraft mc, AwarenessState state) {
        if (state.observations().isEmpty() || state.phase() == AwarenessManager.Phase.NAVIGATING) return;
        var observation = state.observations().get(0);
        String line = observation.severity() + " · " + observation.message();
        int width = mc.font.width(line);
        int x = gui.guiWidth() - width - 10;
        int y = gui.guiHeight() - 20;
        // Terrain observations become visible when they can affect the next action rather
        // than occupying HUD space throughout long-distance navigation.
        gui.drawString(mc.font, line, x, y, 0xFFFFD36A, true);
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

            // The active Wayfinder already owns the strong navigation cue. Do not render
            // the same reference again as a passive edge pin.
            var active = dev.dwoodard.voxelpilot.wayfinder.WayfinderManager.get().active();
            if (active.isPresent()
                && active.get().kind() == dev.dwoodard.voxelpilot.wayfinder.WayfinderManager.TargetKind.REFERENCE
                && active.get().query().equalsIgnoreCase(reference.token())) continue;

            // Unknown/other-dimension facts get no directional marker (there's nothing to
            // point at), but pinning something and having it vanish silently is worse than
            // an honest "position unknown" label. Stack these in the left column with the
            // directional pins.
            if (!reference.hasPosition()) {
                String line = reference.name().toUpperCase() + " · UNKNOWN";
                gui.drawString(mc.font, line, 8, leftY, 0xFF8A9098, true);
                leftY += 12;
                continue;
            }
            if (!mc.level.dimension().location().toString().equals(reference.dimension())) {
                String line = reference.name().toUpperCase() + " · OTHER DIMENSION";
                gui.drawString(mc.font, line, 8, leftY, 0xFF8A9098, true);
                leftY += 12;
                continue;
            }

            RelativeBearing.Local local = RelativeBearing.toLocal(mc, reference.x() - mc.player.getX(), reference.z() - mc.player.getZ());
            int right = (int) Math.round(local.right());
            int forward = (int) Math.round(local.forward());
            int up = (int) Math.round(reference.y() - mc.player.getY());

            String label = reference.name().toUpperCase() + " " + RelativeBearing.deltaLabel(right, forward, up);

            // Still pick one edge to draw on (same placement logic as before) based on the
            // dominant axis -- only the label text changed, from a single arrow+total-distance
            // to the same per-axis delta vocabulary used everywhere else.
            if (Math.abs(right) >= Math.abs(forward)) {
                if (right < 0) {
                    gui.drawString(mc.font, label, 8, leftY, 0xFFB8C0C8, true);
                    leftY += 12;
                } else {
                    int width = mc.font.width(label);
                    gui.drawString(mc.font, label, gui.guiWidth() - width - 8, rightY, 0xFFB8C0C8, true);
                    rightY += 12;
                }
            } else if (forward > 0) {
                gui.drawString(mc.font, label, topX, 8, 0xFFB8C0C8, true);
                topX += mc.font.width(label) + 14;
            } else {
                gui.drawString(mc.font, label, bottomX, bottom, 0xFFB8C0C8, true);
                bottomX += mc.font.width(label) + 14;
            }
        }
    }

    private static String verticalLabel(int vertical) {
        if (vertical == 0) return "LEVEL";
        return (vertical < 0 ? "BELOW " : "ABOVE ") + Math.abs(vertical) + "m";
    }
}
