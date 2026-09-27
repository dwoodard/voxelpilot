package dev.dwoodard.voxelpilot.reference;

import java.util.ArrayList;
import java.util.List;

/** Session-scoped HUD pins identified by shared @ reference token. */
public final class ReferencePins {
    private static final ReferencePins INSTANCE = new ReferencePins();
    private static final int MAX_PINS = 4;
    private final List<String> tokens = new ArrayList<>();

    private ReferencePins() {}

    public static ReferencePins get() { return INSTANCE; }

    public synchronized boolean pin(String token) {
        String normalized = normalize(token);
        if (normalized.isBlank()) return false;
        tokens.removeIf(existing -> existing.equalsIgnoreCase(normalized));
        if (tokens.size() >= MAX_PINS) tokens.remove(0);
        tokens.add(normalized);
        return true;
    }

    public synchronized boolean unpin(String token) {
        String normalized = normalize(token);
        return tokens.removeIf(existing -> existing.equalsIgnoreCase(normalized));
    }

    public synchronized List<String> tokens() {
        return List.copyOf(tokens);
    }

    private static String normalize(String token) {
        if (token == null) return "";
        String value = token.trim();
        if (!value.startsWith("@")) value = "@" + value;
        return value;
    }
}
