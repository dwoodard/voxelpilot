package dev.dwoodard.voxelpilot.ai;

import java.util.ArrayList;
import java.util.List;

// The last few AI requests and what became of them (previewed, skipped lines, confirmed,
// cancelled, undone). Sent with every request so "do the same over there", "that didn't
// work", and "undo that and try again" mean something, and so the model hears about its
// own rejected lines instead of repeating them. Cleared by cls.
public final class RecentHistory {
    private static final int KEEP = 3;
    private static final int SCRIPT_LINES = 8;
    private static final RecentHistory INSTANCE = new RecentHistory();

    public static final class Entry {
        private final String request;
        private List<String> script = List.of();
        private String result;

        private Entry(String request) { this.request = request; }

        public synchronized void finish(List<String> script, String result) {
            this.script = List.copyOf(script);
            this.result = result;
        }

        public synchronized void fail(String result) { this.result = result; }
    }

    private final List<Entry> entries = new ArrayList<>();

    private RecentHistory() {}
    public static RecentHistory get() { return INSTANCE; }

    public synchronized Entry start(String request) {
        Entry entry = new Entry(request);
        entries.add(entry);
        while (entries.size() > KEEP + 1) entries.remove(0);
        return entry;
    }

    // Confirm / cancel / undo apply to the latest request that produced a script.
    public synchronized void mark(String status) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            synchronized (e) {
                if (!e.script.isEmpty() && e.result != null) {
                    e.result = e.result + " -> " + status;
                    return;
                }
            }
        }
    }

    public synchronized void clear() { entries.clear(); }

    // The most recent finished requests (the one being planned right now is left out),
    // oldest first.
    public synchronized String render() {
        List<Entry> finished = new ArrayList<>();
        for (Entry e : entries) {
            synchronized (e) { if (e.result != null) finished.add(e); }
        }
        StringBuilder out = new StringBuilder();
        for (Entry e : finished.subList(Math.max(0, finished.size() - KEEP), finished.size())) {
            synchronized (e) {
                out.append("- \"").append(e.request).append("\" -> ").append(e.result).append('\n');
                for (int i = 0; i < Math.min(SCRIPT_LINES, e.script.size()); i++) out.append("    ").append(e.script.get(i)).append('\n');
                if (e.script.size() > SCRIPT_LINES) out.append("    (+").append(e.script.size() - SCRIPT_LINES).append(" more lines)\n");
            }
        }
        return out.toString();
    }
}
