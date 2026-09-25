package dev.dwoodard.voxelpilot.ui;

import dev.dwoodard.voxelpilot.ai.AiConversation;
import dev.dwoodard.voxelpilot.ai.ChatMessage;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

// Read-only status readout: Selection, Preview, Build, Bridge, AI Conversation.
// Toggled by Cmd+Shift+K. Separate from SettingsScreen (Cmd+,), which is the only
// place provider/model configuration lives.
public final class InspectorScreen extends Screen {
    public InspectorScreen() { super(Component.literal("VoxelPilot Inspector")); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelW = Math.min(430, width);
        int x = width - panelW;
        g.fill(x, 0, width, height, 0xF216181B);
        g.drawString(font, "VOXELPILOT INSPECTOR", x + 16, 50, 0xFFB5BAC1, false);
        renderStatusPanel(g, font, x);
        super.render(g, mouseX, mouseY, partialTick);
    }

    static void renderStatusPanel(GuiGraphics g, Font font, int x) {
        int y = 78;
        var selection = SelectionManager.get().box();
        g.drawString(font, "SELECTION", x + 16, y, 0xFF8B949E, false); y += 15;
        g.drawString(font, selection.map(b -> b.width() + " × " + b.height() + " × " + b.depth() + "  (" + b.volume() + " blocks)").orElse("None"), x + 16, y, 0xFFF0F2F4, false); y += 30;

        g.drawString(font, "PREVIEW", x + 16, y, 0xFF8B949E, false); y += 15;
        var plan = GhostPreviewManager.get().plan();
        if (plan.isPresent()) {
            var p = plan.get();
            g.drawString(font, p.plan().title + " · rev " + GhostPreviewManager.get().revision() + " · " + p.changes().size() + " changes", x + 16, y, 0xFFF0F2F4, false); y += 14;
            g.drawString(font, "Mode: " + p.mode() + " · replaces " + p.replacedExisting() + " existing · " + p.plan().nodes.size() + " components", x + 16, y, 0xFFB5BAC1, false); y += 14;
            int w = p.max().getX() - p.min().getX() + 1, h = p.max().getY() - p.min().getY() + 1, d = p.max().getZ() - p.min().getZ() + 1;
            g.drawString(font, "Affects " + w + " × " + h + " × " + d + (p.outsideSelection() > 0 ? " · " + p.outsideSelection() + " outside selection" : ""),
                x + 16, y, p.outsideSelection() > 0 ? 0xFFE3B341 : 0xFFB5BAC1, false); y += 14;
            for (String note : p.notes()) { g.drawString(font, "• " + note, x + 16, y, 0xFF8B949E, false); y += 12; }
            y += 10;
        } else { g.drawString(font, "None", x + 16, y, 0xFFF0F2F4, false); y += 28; }

        g.drawString(font, "BUILD", x + 16, y, 0xFF8B949E, false); y += 15;
        BuildExecutor be = BuildExecutor.get();
        String build = be.active() ? (be.completed() + " / " + be.total() + (be.paused() ? " · paused" : " · " + be.speed().name().toLowerCase())) : "Idle";
        g.drawString(font, build, x + 16, y, 0xFFF0F2F4, false); y += 30;

        g.drawString(font, "BRIDGE", x + 16, y, 0xFF8B949E, false); y += 15;
        g.drawString(font, BridgeServer.get().isRunning() ? "Connected · localhost:8765" : "Off · starts with Cmd+K", x + 16, y, 0xFFF0F2F4, false); y += 30;

        g.drawString(font, "AI CONVERSATION", x + 16, y, 0xFF8B949E, false); y += 15;
        List<ChatMessage> turns = AiConversation.get().turns();
        if (turns.isEmpty()) {
            g.drawString(font, "No active conversation", x + 16, y, 0xFFF0F2F4, false);
        } else {
            g.drawString(font, turns.size() + " messages this operation", x + 16, y, 0xFFF0F2F4, false); y += 14;
            String lastUser = "";
            for (int i = turns.size() - 1; i >= 0; i--) {
                if ("user".equals(turns.get(i).role())) { lastUser = turns.get(i).content(); break; }
            }
            String preview = lastUser.split("\n\nWORLD CONTEXT:", 2)[0];
            if (preview.length() > 55) preview = preview.substring(0, 52) + "...";
            g.drawString(font, "Last: " + preview, x + 16, y, 0xFFB5BAC1, false); y += 14;
            g.drawString(font, "Resets on confirm/cancel/clear selection", x + 16, y, 0xFF8B949E, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
