package dev.dwoodard.voxelpilot.ui;

import dev.dwoodard.voxelpilot.ai.CommandProcessor;
import dev.dwoodard.voxelpilot.ai.PaletteHistory;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CommandPaletteScreen extends Screen {
    private static final int MAX_TRANSCRIPT_LINES = 6;

    private EditBox input;
    private String status = "Type what you want VoxelPilot to do";
    private List<String> suggestions = List.of();
    private int selected;
    // Shell-style Up/Down command recall over past submitted inputs (PaletteHistory's
    // "you" entries). -1 means "not currently browsing history".
    private int historyIndex = -1;
    private String draftBeforeHistory = "";

    public CommandPaletteScreen() { super(Component.literal("VoxelPilot")); }

    @Override
    protected void init() {
        int w = Math.min(620, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(30, height / 5);
        input = new EditBox(font, x + 16, y + 16, w - 32, 24, Component.literal("Ask VoxelPilot"));
        input.setMaxLength(500);
        input.setHint(Component.literal("Build, modify, confirm, move, inspect…"));
        input.setResponder(value -> { selected = 0; updateSuggestions(value); });
        addRenderableWidget(input);
        setInitialFocus(input);
        updateSuggestions("");
    }

    private void updateSuggestions(String query) {
        List<String> items = new ArrayList<>();
        if (GhostPreviewManager.get().hasPreview()) {
            items.add("confirm preview");
            items.add("make the preview taller");
            items.add("move me somewhere I can see the whole build");
            items.add("cancel preview");
        } else if (BuildExecutor.get().active()) {
            items.add(BuildExecutor.get().paused() ? "resume" : "pause");
            items.add("speed fast");
            items.add("speed normal");
            items.add("cancel");
        } else if (SelectionManager.get().box().isPresent()) {
            items.add("build a medieval barn in this area");
            items.add("analyze this area before building");
            items.add("flatten this area");
            items.add("clear selection");
        } else {
            items.add("build something where I'm looking");
            items.add("finish this structure");
            items.add("move me somewhere with a better view");
            items.add("undo");
        }
        items.add("speed slow");
        items.add("speed normal");
        items.add("speed fast");
        items.add("speed instant");
        items.add("settings");

        String needle = query.toLowerCase(Locale.ROOT).trim();
        suggestions = items.stream().filter(s -> needle.isEmpty() || s.toLowerCase(Locale.ROOT).contains(needle)).distinct().limit(6).toList();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            List<String> pastCommands = PaletteHistory.get().entries().stream()
                .filter(e -> "you".equals(e.who())).map(PaletteHistory.Entry::text).toList();
            if (!pastCommands.isEmpty()) {
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    if (historyIndex == -1) draftBeforeHistory = input.getValue();
                    historyIndex = historyIndex == -1 ? pastCommands.size() - 1 : Math.max(0, historyIndex - 1);
                    input.setValue(pastCommands.get(historyIndex));
                } else if (historyIndex != -1) {
                    if (historyIndex < pastCommands.size() - 1) {
                        historyIndex++;
                        input.setValue(pastCommands.get(historyIndex));
                    } else {
                        historyIndex = -1;
                        input.setValue(draftBeforeHistory);
                    }
                }
                input.setCursorPosition(input.getValue().length());
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !suggestions.isEmpty()) {
            input.setValue(suggestions.get(selected));
            input.setCursorPosition(input.getValue().length());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            String command = input.getValue().trim();
            if (command.isEmpty() && !suggestions.isEmpty()) command = suggestions.get(selected);
            if (!command.isEmpty()) {
                String finalCommand = command;
                status = "Working…";
                historyIndex = -1;
                CommandProcessor.run(Minecraft.getInstance(), finalCommand, value -> status = value);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int w = Math.min(620, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(30, height / 5);

        List<PaletteHistory.Entry> history = PaletteHistory.get().entries();
        int start = Math.max(0, history.size() - MAX_TRANSCRIPT_LINES);
        List<PaletteHistory.Entry> transcript = history.subList(start, history.size());
        boolean showWorking = "Working…".equals(status);
        boolean showHint = transcript.isEmpty() && !showWorking;

        int linesShown = transcript.size() + (showWorking || showHint ? 1 : 0);
        int rowsHeight = suggestions.size() * 22;
        int boxHeight = 66 + linesShown * 12 + 10 + rowsHeight + 20;
        graphics.fill(x, y, x + w, y + boxHeight, 0xEE15171A);
        graphics.drawString(font, "VOXELPILOT", x + 16, y + 4, 0xFF9AA0A6, false);

        int ty = y + 52;
        for (PaletteHistory.Entry entry : transcript) {
            boolean isYou = "you".equals(entry.who());
            String text = (isYou ? "> " : "") + entry.text();
            if (text.length() > 95) text = text.substring(0, 92) + "...";
            graphics.drawString(font, text, x + 16, ty, isYou ? 0xFF9AA0A6 : 0xFFE8EAED, false);
            ty += 12;
        }
        if (showWorking || showHint) {
            graphics.drawString(font, status, x + 16, ty, 0xFF9AA0A6, false);
            ty += 12;
        }

        int sy = ty + 8;
        for (int i = 0; i < suggestions.size(); i++) {
            int bg = i == selected ? 0xFF2B3035 : 0x00151515;
            graphics.fill(x + 10, sy + i * 22, x + w - 10, sy + i * 22 + 20, bg);
            graphics.drawString(font, suggestions.get(i), x + 18, sy + i * 22 + 6, 0xFFE8EAED, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(dev.dwoodard.voxelpilot.client.ClientEvents.inspectorOpen ? new InspectorScreen() : null);
    }
}
