package dev.dwoodard.voxelpilot.ai;

import java.util.function.Consumer;

// Turns streamed text fragments into complete lines as soon as each newline arrives,
// so the first script line can be previewed while the model is still writing the rest.
public final class LineSplitter {
    private final Consumer<String> onLine;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder all = new StringBuilder();

    public LineSplitter(Consumer<String> onLine) { this.onLine = onLine; }

    public void accept(String fragment) {
        if (fragment == null || fragment.isEmpty()) return;
        all.append(fragment);
        pending.append(fragment);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            emit(pending.substring(0, newline));
            pending.delete(0, newline + 1);
        }
    }

    public void flush() {
        if (!pending.isEmpty()) emit(pending.toString());
        pending.setLength(0);
    }

    public String text() { return all.toString(); }

    private void emit(String line) {
        String trimmed = line.strip();
        if (!trimmed.isEmpty()) onLine.accept(trimmed);
    }
}
