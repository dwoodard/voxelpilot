package dev.dwoodard.voxelpilot.ai;

import java.util.ArrayList;
import java.util.List;

// Multi-turn history for the current AI operation. Per AGENTS.md, an "operation" is one
// confirmed AI world mutation - so this resets whenever the preview is cleared (confirm,
// cancel, or "clear selection"), rather than accumulating context across unrelated builds.
// Without this, every follow-up ("make it taller") was a blind one-shot request that never
// saw the actual prior plan, only a one-line text summary of it.
public final class AiConversation {
    private static final AiConversation INSTANCE = new AiConversation();
    private final List<ChatMessage> turns = new ArrayList<>();

    private AiConversation() {}
    public static AiConversation get() { return INSTANCE; }

    public synchronized List<ChatMessage> turns() { return List.copyOf(turns); }
    public synchronized void addUserTurn(String prompt) { turns.add(new ChatMessage("user", prompt)); }
    public synchronized void addAssistantTurn(String rawJson) { turns.add(new ChatMessage("assistant", rawJson)); }
    public synchronized void reset() { turns.clear(); }
}
