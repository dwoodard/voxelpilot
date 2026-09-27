package dev.dwoodard.voxelpilot.ui;

import dev.dwoodard.voxelpilot.ai.CommandProcessor;
import dev.dwoodard.voxelpilot.ai.PaletteHistory;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import dev.dwoodard.voxelpilot.reference.ReferencePins;
import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class CommandPaletteScreen extends Screen {
    private static final int MAX_TRANSCRIPT_LINES = 6;

    private EditBox input;
    private String status = "Type what you want VoxelPilot to do";
    private List<PaletteSuggestionService.Item> suggestions = List.of();
    private int selected;
    private String actionReference;

    public CommandPaletteScreen() { super(Component.literal("VoxelPilot")); }

    @Override
    protected void init() {
        int w = Math.min(620, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(30, height / 5);
        input = new EditBox(font, x + 16, y + 16, w - 32, 24, Component.literal("Ask VoxelPilot"));
        input.setMaxLength(500);
        input.setHint(Component.literal("/ command   @ reference   # designate   or ask VoxelPilot…"));
        input.setResponder(value -> { selected = 0; updateSuggestions(value); });
        addRenderableWidget(input);
        setInitialFocus(input);
        updateSuggestions("");
    }

    private void updateSuggestions(String query) {
        PaletteSuggestionService.suggestions(Minecraft.getInstance(), query, input == null ? query.length() : input.getCursorPosition(), items -> {
            suggestions = items;
            if (selected >= suggestions.size()) selected = Math.max(0, suggestions.size() - 1);
        });
    }


    public void showShortcuts() {
        status = ShortcutRegistry.displayText();
        PaletteHistory.get().addAssistant(status);
    }

    private String selectedReferenceToken() {
        if (actionReference != null) return actionReference;
        if (!suggestions.isEmpty()) {
            var item = suggestions.get(Math.min(selected, suggestions.size() - 1));
            if (item.source() == PaletteSuggestionService.Source.REFERENCE) {
                String[] parts = item.label().split("\\s+");
                if (parts.length > 0 && parts[0].startsWith("@")) return parts[0];
            }
        }
        String value = input.getValue().trim();
        if (value.startsWith("@") && !value.contains(" ")) return value;
        return null;
    }

    private void openReferenceActions() {
        String token = selectedReferenceToken();
        if (token == null) { status = "Select an @reference first"; return; }
        var reference = ReferenceResolver.resolve(Minecraft.getInstance(), token.substring(1));
        if (reference.isEmpty()) { status = token + " is unknown"; return; }
        actionReference = reference.get().token();
        selected = 0;
        boolean pinned = ReferencePins.get().isPinned(actionReference);
        suggestions = List.of(
            new PaletteSuggestionService.Item("/wayfinder " + actionReference, "Wayfind " + actionReference, PaletteSuggestionService.Source.VOXELPILOT),
            new PaletteSuggestionService.Item((pinned ? "/unpin " : "/pin ") + actionReference,
                (pinned ? "Unpin " : "Pin ") + actionReference, PaletteSuggestionService.Source.VOXELPILOT),
            new PaletteSuggestionService.Item(actionReference, "Inspect " + actionReference, PaletteSuggestionService.Source.VOXELPILOT)
        );
        status = reference.get().paletteLabel();
    }

    private void runReferenceAction(String command) {
        status = "Working…";
        CommandProcessor.run(Minecraft.getInstance(), command, value -> status = value);
        actionReference = null;
    }

    private void toggleSelectedReferencePin() {
        Minecraft mc = Minecraft.getInstance();
        String token = selectedReferenceToken();

        if (token == null) {
            status = "Cmd+P requires a selected @reference";
            return;
        }

        String resolvedToken = token;
        var reference = ReferenceResolver.resolve(mc, resolvedToken.substring(1));
        if (reference.isEmpty()) {
            status = resolvedToken + " is unknown";
            return;
        }

        String canonical = reference.get().token();
        if (ReferencePins.get().isPinned(canonical)) {
            ReferencePins.get().unpin(canonical);
            status = "Unpinned " + canonical;
        } else {
            ReferencePins.get().pin(canonical);
            status = "Pinned " + canonical + " to HUD";
        }
    }

    private void acceptSuggestion() {
        if (suggestions.isEmpty()) return;
        PaletteSuggestionService.Item suggestion = suggestions.get(Math.min(selected, suggestions.size() - 1));
        String value = suggestion.value();

        // Suggestion values already contain the complete input with only the active token
        // replaced, so references and server-command arguments remain composable.
        input.setValue(value);
        input.setCursorPosition(input.getValue().length());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && actionReference != null) {
            actionReference = null;
            selected = 0;
            updateSuggestions(input.getValue());
            status = "Type what you want VoxelPilot to do";
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) && !suggestions.isEmpty()) {
            selected = keyCode == GLFW.GLFW_KEY_UP
                ? (selected - 1 + suggestions.size()) % suggestions.size()
                : (selected + 1) % suggestions.size();
            return true;
        }
        boolean commandModifier = (modifiers & (GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_CONTROL)) != 0;
        boolean shiftModifier = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (commandModifier) {
            var shortcut = ShortcutRegistry.match(keyCode, shiftModifier);
            if (shortcut.isPresent()
                && !(shortcut.get().key() == GLFW.GLFW_KEY_ENTER && shortcut.get().shift())) {
                if (shortcut.get().key() == GLFW.GLFW_KEY_P) {
                    toggleSelectedReferencePin();
                    return true;
                }
                if (shortcut.get().key() == GLFW.GLFW_KEY_G) {
                    String token = selectedReferenceToken();
                    if (token == null) status = "Cmd+G requires a selected @reference";
                    else runReferenceAction("/wayfinder " + token);
                    return true;
                }
                if (shortcut.get().key() == GLFW.GLFW_KEY_I) {
                    String token = selectedReferenceToken();
                    if (token == null) status = "Cmd+I requires a selected @reference";
                    else runReferenceAction(token);
                    return true;
                }
                status = "Working…";
                CommandProcessor.run(Minecraft.getInstance(), shortcut.get().command(), value -> status = value);
                return true;
            }
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
            if (actionReference != null && !suggestions.isEmpty()) {
                runReferenceAction(suggestions.get(Math.min(selected, suggestions.size() - 1)).value());
                return true;
            }
            String command = input.getValue().trim();
            if (command.isEmpty() && !suggestions.isEmpty()) command = suggestions.get(selected).value();

            // Tab completes. Enter submits the input exactly as typed; standalone @ references
            // are deterministic and handled by CommandProcessor without invoking AI.
            if (!command.isEmpty()) {
                String finalCommand = command;
                CommandUsageStore.get().record(finalCommand);
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
        if (keyCode == GLFW.GLFW_KEY_RIGHT && !suggestions.isEmpty()) {
            if (suggestions.get(Math.min(selected, suggestions.size() - 1)).source() == PaletteSuggestionService.Source.REFERENCE) {
                openReferenceActions();
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT && actionReference != null) {
            actionReference = null;
            selected = 0;
            updateSuggestions(input.getValue());
            status = "Type what you want VoxelPilot to do";
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (handled && input != null) updateSuggestions(input.getValue());
        return handled;
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

        int textWidth = w - 32;
        int linesShown = 0;
        for (PaletteHistory.Entry entry : transcript) {
            String text = ("you".equals(entry.who()) ? "> " : "") + entry.text();
            linesShown += Math.max(1, font.split(Component.literal(text), textWidth).size());
        }
        if (showWorking || showHint) {
            linesShown += Math.max(1, font.split(Component.literal(status), textWidth).size());
        }
        int rowsHeight = suggestions.size() * 22;
        int boxHeight = 66 + linesShown * 12 + 10 + rowsHeight + 20;
        graphics.fill(x, y, x + w, y + boxHeight, 0xEE15171A);
        graphics.drawString(font, "VOXELPILOT", x + 16, y + 4, 0xFF9AA0A6, false);

        int ty = y + 52;
        for (PaletteHistory.Entry entry : transcript) {
            boolean isYou = "you".equals(entry.who());
            String text = (isYou ? "> " : "") + entry.text();
            for (var line : font.split(Component.literal(text), textWidth)) {
                graphics.drawString(font, line, x + 16, ty, isYou ? 0xFF9AA0A6 : 0xFFE8EAED, false);
                ty += 12;
            }
        }
        if (showWorking || showHint) {
            for (var line : font.split(Component.literal(status), textWidth)) {
                graphics.drawString(font, line, x + 16, ty, 0xFF9AA0A6, false);
                ty += 12;
            }
        }

        int sy = ty + 8;
        for (int i = 0; i < suggestions.size(); i++) {
            int bg = i == selected ? 0xFF2B3035 : 0x00151515;
            graphics.fill(x + 10, sy + i * 22, x + w - 10, sy + i * 22 + 20, bg);
            PaletteSuggestionService.Item suggestion = suggestions.get(i);
            graphics.drawString(font, suggestion.label(), x + 18, sy + i * 22 + 6, 0xFFE8EAED, false);
            String source = suggestion.source().name();
            int sourceWidth = font.width(source);
            graphics.drawString(font, source, x + w - 18 - sourceWidth, sy + i * 22 + 6, 0xFF777D84, false);
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
