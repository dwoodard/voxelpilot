package dev.dwoodard.voxelpilot.util;

import net.minecraft.client.Minecraft;

// Shared player-relative direction math and formatting, used anywhere something needs to say
// where a thing is relative to the player: the HUD bearing readout, pinned references, and
// /wayfinder's suggestion preview. One vocabulary (right/left, ahead/behind, up/down arrows
// with a per-axis magnitude) instead of each caller inventing its own distance/direction text.
public final class RelativeBearing {
    private RelativeBearing() {}

    public record Local(double right, double forward) {}

    // Rotates a world-space offset (dx, dz) into the player's own frame: right > 0 is to
    // their right, forward > 0 is ahead of them.
    public static Local toLocal(Minecraft mc, double dx, double dz) {
        double yaw = Math.toRadians(mc.player.getYRot());
        double right = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        double localZ = dz * Math.cos(yaw) - dx * Math.sin(yaw);
        return new Local(right, -localZ);
    }

    // "▶3 ▲12 ⬆2": one glyph+magnitude per non-zero axis, omitting axes that are already zero.
    // "here" if all three are zero (the target is where the player is standing).
    public static String deltaLabel(int right, int forward, int up) {
        StringBuilder label = new StringBuilder();
        if (right != 0) label.append(right < 0 ? "◀" : "▶").append(Math.abs(right)).append(' ');
        if (forward != 0) label.append(forward > 0 ? "▲" : "▼").append(Math.abs(forward)).append(' ');
        if (up != 0) label.append(up > 0 ? "⬆" : "⬇").append(Math.abs(up)).append(' ');
        if (label.isEmpty()) return "here";
        return label.substring(0, label.length() - 1);
    }
}
