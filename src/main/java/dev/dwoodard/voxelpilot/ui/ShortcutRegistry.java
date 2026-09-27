package dev.dwoodard.voxelpilot.ui;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of truth for Voxel Pilot keyboard accelerators.
 * Keep bindings here so keyboard handling and /shortcuts cannot drift apart.
 */
public final class ShortcutRegistry {
    public record Shortcut(String keys, String label, int key, boolean shift, String command) {}

    private static final List<Shortcut> SHORTCUTS = List.of(
        new Shortcut("Cmd+N", "Find Next", GLFW.GLFW_KEY_N, false, "/wayfinder next"),
        new Shortcut("Cmd+P", "Pin / Unpin reference", GLFW.GLFW_KEY_P, false, null),
        new Shortcut("Cmd+G", "Wayfind selected reference", GLFW.GLFW_KEY_G, false, null),
        new Shortcut("Cmd+I", "Inspect selected reference", GLFW.GLFW_KEY_I, false, null),
        new Shortcut("Cmd+Z", "Undo last build", GLFW.GLFW_KEY_Z, false, "undo build"),
        new Shortcut("Cmd+Shift+Enter", "Confirm preview", GLFW.GLFW_KEY_ENTER, true, "confirm preview")
    );

    private ShortcutRegistry() {}

    public static List<Shortcut> all() {
        return SHORTCUTS;
    }

    public static java.util.Optional<Shortcut> match(int key, boolean shift) {
        return SHORTCUTS.stream()
            .filter(shortcut -> shortcut.key() == key && shortcut.shift() == shift)
            .findFirst();
    }

    public static String displayText() {
        String rows = SHORTCUTS.stream()
            .map(shortcut -> shortcut.keys() + "  " + shortcut.label())
            .collect(Collectors.joining("\n"));
        return "VOXEL PILOT SHORTCUTS\n"
            + "Cmd+K  Open / close Voxel Pilot\n"
            + "Cmd+/  Show this list\n"
            + "Cmd+,  Settings\n"
            + "Cmd+L  Reset palette history / preview\n"
            + rows;
    }
}
