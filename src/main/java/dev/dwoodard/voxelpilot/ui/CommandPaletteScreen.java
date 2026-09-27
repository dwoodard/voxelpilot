package dev.dwoodard.voxelpilot.ui;

import dev.dwoodard.voxelpilot.ai.CommandProcessor;
import dev.dwoodard.voxelpilot.ai.PaletteHistory;
import dev.dwoodard.voxelpilot.ai.PaletteSuggestion;
import dev.dwoodard.voxelpilot.ai.PaletteUsage;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
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
    private List<PaletteSuggestion> suggestions = List.of();
    private int selected;
    private List<WayfinderManager.Suggestion> wayfinderSuggestions = List.of();

    public CommandPaletteScreen() { super(Component.literal("VoxelPilot")); }

    @Override
    protected void init() {
        int w = Math.min(620, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(30, height / 5);
        input = new EditBox(font, x + 16, y + 16, w - 32, 24, Component.literal("Ask VoxelPilot"));
        input.setMaxLength(500);
        input.setHint(Component.literal("/ action   @ reference   or type what you want…"));
        input.setResponder(value -> { selected = 0; updateSuggestions(value); });
        addRenderableWidget(input);
        setInitialFocus(input);
        updateSuggestions("");
    }

    private void updateSuggestions(String query) {
        String trimmed = query.trim();

        // @ is a composable reference token. Start with facts the client knows directly:
        // online players. Places/designations can join this provider later without changing
        // the palette interaction contract.
        int at = query.lastIndexOf('@');
        if (at >= 0 && (at == 0 || Character.isWhitespace(query.charAt(at - 1)))) {
            String needle = query.substring(at + 1).trim();
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                suggestions = PaletteUsage.get().rank(
                    mc.getConnection().getOnlinePlayers().stream()
                        .map(info -> {
                            String name = info.getProfile().getName();
                            return new PaletteSuggestion("@" + name, "@" + name, "PLAYER");
                        }).toList(),
                    "@" + needle,
                    6);
                return;
            }
        }

        if (trimmed.toLowerCase(Locale.ROOT).startsWith("/wayfinder")) {
            String targetQuery = trimmed.length() > 10 ? trimmed.substring(10).trim() : "";
            wayfinderSuggestions = WayfinderManager.get().suggestions(targetQuery, 12);
            suggestions = PaletteUsage.get().rank(
                wayfinderSuggestions.stream()
                    .map(s -> new PaletteSuggestion(s.name() + "  ·  " + s.id(), "/wayfinder " + s.id(), "VOXEL PILOT"))
                    .toList(),
                trimmed,
                6);
            return;
        }

        wayfinderSuggestions = List.of();
        List<PaletteSuggestion> items = new ArrayList<>();
        if (GhostPreviewManager.get().hasPreview()) {
            items.add(local("confirm preview"));
            items.add(local("make the preview taller"));
            items.add(local("cancel preview"));
        } else if (BuildExecutor.get().active()) {
            items.add(local(BuildExecutor.get().paused() ? "resume build" : "pause build"));
            items.add(local("speed fast"));
            items.add(local("speed normal"));
        } else if (SelectionManager.get().box().isPresent()) {
            items.add(local("build a medieval barn in this area"));
            items.add(local("analyze this area before building"));
            items.add(local("flatten this area"));
            items.add(local("clear selection"));
        } else {
            items.add(new PaletteSuggestion("/wayfinder diamond", "/wayfinder diamond", "VOXEL PILOT"));
            items.add(local("build something where I'm looking"));
            items.add(local("finish this structure"));
            items.add(local("undo build"));
        }
        items.add(local("speed slow"));
        items.add(local("speed normal"));
        items.add(local("speed fast"));
        items.add(local("speed instant"));
        items.add(local("settings"));

        // Successful/recent user inputs become candidates instead of taking over Up/Down.
        // Slash entries are especially useful until server Brigadier completion is wired in.
        for (PaletteHistory.Entry entry : PaletteHistory.get().entries()) {
            if (!"you".equals(entry.who())) continue;
            String value = entry.text().trim();
            if (value.isEmpty()) continue;
            if (trimmed.startsWith("/") && !value.startsWith("/")) continue;
            items.add(new PaletteSuggestion(value, value, "RECENT"));
        }

        suggestions = PaletteUsage.get().rank(items, trimmed, 6);
    }

    private static PaletteSuggestion local(String value) {
        return new PaletteSuggestion(value, value, "VOXEL PILOT");
    }

    private void acceptSuggestion() {
        if (suggestions.isEmpty()) return;
        PaletteSuggestion suggestion = suggestions.get(Math.min(selected, suggestions.size() - 1));
        String value = suggestion.value();

        // Replace only the active @ token so references compose with commands and prose.
        int at = input.getValue().lastIndexOf('@');
        if (value.startsWith("@") && at >= 0) {
            input.setValue(input.getValue().substring(0, at) + value);
        } else {
            input.setValue(value);
        }
        input.setCursorPosition(input.getValue().length());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) && !suggestions.isEmpty()) {
            selected = keyCode == GLFW.GLFW_KEY_UP
                ? (selected - 1 + suggestions.size()) % suggestions.size()
                : (selected + 1) % suggestions.size();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_L && (modifiers & (GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_CONTROL)) != 0) {
            // Same as cls: ghost, chat, and AI history.
            dev.dwoodard.voxelpilot.build.GhostPreviewManager.get().clear();
            PaletteHistory.get().clear();
            dev.dwoodard.voxelpilot.ai.RecentHistory.get().clear();
            status = "Reset · preview, chat, and AI history cleared";
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !suggestions.isEmpty()) {
            acceptSuggestion();
            return true;
        }
        // Cmd+Shift+Enter confirms the preview from inside the palette too, whatever is typed.
        boolean cmd = (modifiers & (GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_CONTROL)) != 0;
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && cmd && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            status = "Working…";
            CommandProcessor.run(Minecraft.getInstance(), "confirm", value -> status = value);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            String command = input.getValue().trim();
            if (command.isEmpty() && !suggestions.isEmpty()) command = suggestions.get(selected).value();

            // A selected @ reference completes the token; it is not itself an action.
            if (!suggestions.isEmpty() && suggestions.get(Math.min(selected, suggestions.size() - 1)).value().startsWith("@")) {
                acceptSuggestion();
                return true;
            }
            if (!command.isEmpty()) {
                String finalCommand = command;
                PaletteUsage.get().record(finalCommand);
                status = "Working…";
                    // A bug in command handling must never take the game down with it.
                try {
                    CommandProcessor.run(Minecraft.getInstance(), finalCommand, value -> status = value);
                } catch (RuntimeException | Error e) {
                    dev.dwoodard.voxelpilot.VoxelPilot.LOGGER.error("VoxelPilot: command failed: {}", finalCommand, e);
                    Throwable root = e;
                    while (root.getCause() != null) root = root.getCause();
                    status = "Error: " + root;
                    PaletteHistory.get().addAssistant(status);
                }
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
            PaletteSuggestion suggestion = suggestions.get(i);
            graphics.drawString(font, suggestion.label(), x + 18, sy + i * 22 + 6, 0xFFE8EAED, false);
            int sourceWidth = font.width(suggestion.source());
            graphics.drawString(font, suggestion.source(), x + w - 18 - sourceWidth, sy + i * 22 + 6, 0xFF777D84, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }

    // Esc or Cmd+K returns directly to the game.
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
