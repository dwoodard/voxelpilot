package dev.dwoodard.voxelpilot.ai;

import java.util.ArrayList;
import java.util.List;

// Human-readable chat transcript for the command palette UI. Separate from
// AiConversation, which stores the raw JSON turns actually sent to the model (and resets
// per-operation to bound token growth) - this is just what gets displayed, so it persists
// across operations; it's capped rather than reset, purely to bound render/memory cost.
public final class PaletteHistory {
    private static final int MAX_ENTRIES = 100;

    public record Entry(String who, String text) {}

    private static final PaletteHistory INSTANCE = new PaletteHistory();
    private final List<Entry> entries = new ArrayList<>();

    private PaletteHistory() {}
    public static PaletteHistory get() { return INSTANCE; }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }
    public synchronized void addUser(String text) { add(new Entry("you", text)); }
    public synchronized void addAssistant(String text) { add(new Entry("voxelpilot", text)); }

    private void add(Entry entry) {
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }
}
