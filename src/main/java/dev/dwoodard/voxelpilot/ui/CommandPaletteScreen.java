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
    private int scrollLeftPane = 0;
    private int scrollRightPane = 0;
    private static String lastInput = "";
    private static int lastSelected = 0;
    private boolean suppressUpdateOnInputChange = false;

    public CommandPaletteScreen() { super(Component.literal("VoxelPilot")); }

    @Override
    protected void init() {
        int w = Math.min(620, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(30, height / 5);
        input = new EditBox(font, x + 16, y + 16, w - 32, 24, Component.literal("Ask VoxelPilot"));
        input.setMaxLength(500);
        input.setHint(Component.literal("/ command   @ reference   # designate   or ask VoxelPilot…"));
        input.setResponder(value -> {
            if (!suppressUpdateOnInputChange) {
                selected = 0;
                updateSuggestions(value);
            }
        });
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

    private String getRightPaneContent() {
        if (actionReference != null) {
            var reference = ReferenceResolver.resolve(Minecraft.getInstance(), actionReference.substring(1));
            if (reference.isPresent()) return reference.get().paletteLabel();
            return "Reference not found";
        }

        if (!suggestions.isEmpty()) {
            var selected = suggestions.get(Math.min(this.selected, suggestions.size() - 1));
            if (selected.source() == PaletteSuggestionService.Source.REFERENCE) {
                String token = selectedReferenceToken();
                if (token != null && token.startsWith("@")) {
                    var reference = ReferenceResolver.resolve(Minecraft.getInstance(), token.substring(1));
                    if (reference.isPresent()) return reference.get().paletteLabel();
                }
            }
        }

        return status;
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
        String before = input.getValue();
        int cursor = input.getCursorPosition();

        suppressUpdateOnInputChange = true;
        input.setValue(value);
        input.setCursorPosition(Math.max(0, Math.min(value.length(), cursor + value.length() - before.length())));
        suppressUpdateOnInputChange = false;
        updateSuggestions(value);
    }

    private void acceptSuggestionWithoutRefilter() {
        if (suggestions.isEmpty()) return;
        PaletteSuggestionService.Item suggestion = suggestions.get(Math.min(selected, suggestions.size() - 1));
        String value = suggestion.value();
        String before = input.getValue();
        int cursor = input.getCursorPosition();

        suppressUpdateOnInputChange = true;
        input.setValue(value);
        input.setCursorPosition(Math.max(0, Math.min(value.length(), cursor + value.length() - before.length())));
        suppressUpdateOnInputChange = false;
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
        boolean shiftModifier = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean commandModifier = (modifiers & (GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_CONTROL)) != 0;

        if (shiftModifier && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            scrollRightPane += keyCode == GLFW.GLFW_KEY_UP ? -2 : 2;
            scrollRightPane = Math.max(0, scrollRightPane);
            return true;
        }

        if (commandModifier && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) && !suggestions.isEmpty()) {
            scrollLeftPane += keyCode == GLFW.GLFW_KEY_UP ? -2 : 2;
            scrollLeftPane = Math.max(0, scrollLeftPane);
            return true;
        }

        if ((keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) && !suggestions.isEmpty()) {
            selected = keyCode == GLFW.GLFW_KEY_UP
                ? (selected - 1 + suggestions.size()) % suggestions.size()
                : (selected + 1) % suggestions.size();
            return true;
        }
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
        if (keyCode == GLFW.GLFW_KEY_C && (modifiers & (GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_CONTROL)) != 0) {
            actionReference = null;
            selected = 0;
            scrollLeftPane = 0;
            scrollRightPane = 0;
            input.setValue("");
            updateSuggestions("");
            PaletteHistory.get().clear();
            status = "Cleared";
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !suggestions.isEmpty()) {
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            acceptSuggestionWithoutRefilter();
            if (shift) {
                selected = (selected - 1 + suggestions.size()) % suggestions.size();
            } else {
                selected = (selected + 1) % suggestions.size();
            }
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
            if (actionReference == null && !suggestions.isEmpty()
                && suggestions.get(Math.min(selected, suggestions.size() - 1)).source() == PaletteSuggestionService.Source.REFERENCE) {
                openReferenceActions();
                return true;
            }
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
        int w = Math.min(1000, width - 40);
        int x = (width - w) / 2;
        int y = Math.max(30, height / 5);

        graphics.fill(x, y, x + w, y + height - y - 40, 0xEE15171A);
        graphics.drawString(font, "VOXELPILOT", x + 16, y + 4, 0xFF9AA0A6, false);

        int leftW = w / 2 - 2;
        int rightW = w / 2 - 2;
        int splitX = x + w / 2;
        int contentY = y + 32;
        int contentH = height - contentY - 40;

        renderLeftPane(graphics, x + 8, contentY, leftW, contentH);
        renderRightPane(graphics, splitX + 4, contentY, rightW, contentH);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderLeftPane(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0x22000000);
        graphics.drawString(font, "COMMANDS", x + 4, y + 2, 0xFF70FF8A, false);

        int sy = y + 16;
        int maxVisible = (h - 16) / 22;
        int startIdx = Math.min(scrollLeftPane / 22, Math.max(0, suggestions.size() - maxVisible));

        for (int i = startIdx; i < Math.min(startIdx + maxVisible, suggestions.size()); i++) {
            int renderY = sy + (i - startIdx) * 22;
            int bg = i == selected ? 0xFF2B3035 : 0x00151515;
            graphics.fill(x + 2, renderY, x + w - 2, renderY + 20, bg);
            PaletteSuggestionService.Item suggestion = suggestions.get(i);
            String label = suggestion.label();
            label = stripNamespace(label);
            if (label.length() > 25) label = label.substring(0, 22) + "…";
            graphics.drawString(font, label, x + 6, renderY + 6, 0xFFE8EAED, false);
        }
    }

    private String stripNamespace(String text) {
        int colon = text.lastIndexOf(':');
        if (colon > 0 && colon < text.length() - 1) {
            return text.substring(colon + 1);
        }
        return text;
    }

    private void renderRightPane(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0x22000000);
        graphics.drawString(font, "FACTS", x + 4, y + 2, 0xFF70FF8A, false);

        String content = getRightPaneContent();
        int textW = w - 12;
        var lines = font.split(Component.literal(content), textW);

        int sy = y + 16 - scrollRightPane;
        for (int i = 0; i < lines.size(); i++) {
            int renderY = sy + i * 12;
            if (renderY > y + h) break;
            if (renderY + 12 > y) {
                graphics.drawString(font, lines.get(i), x + 6, renderY, 0xFFE8EAED, false);
            }
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    // Esc or Cmd+K returns directly to the game.
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
