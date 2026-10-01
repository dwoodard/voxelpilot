package dev.dwoodard.voxelpilot.ui;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.dwoodard.voxelpilot.build.BuildSpeed;
import dev.dwoodard.voxelpilot.util.RelativeBearing;
import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class PaletteSuggestionService {
    public enum Source { SERVER, REFERENCE, VOXELPILOT, RECENT, PREVIEW }

    public record Item(String value, String label, Source source) {}

    // Kept in sync with the "/clear ..." cases in CommandProcessor.
    private static final List<String> CLEAR_TARGETS = List.of("all", "selection", "preview", "wayfinder", "pins", "chat");

    // Kept in sync with the fixed-name cases in CommandProcessor's switch. LinkedHashMap so
    // suggestions appear in a stable, deliberate order.
    private static final Map<String, String> SIMPLE_COMMANDS = new LinkedHashMap<>();
    static {
        SIMPLE_COMMANDS.put("/confirm", "confirm the preview");
        SIMPLE_COMMANDS.put("/undo", "undo the last build");
        SIMPLE_COMMANDS.put("/pause", "pause the active build");
        SIMPLE_COMMANDS.put("/resume", "resume a paused build");
        SIMPLE_COMMANDS.put("/settings", "open settings");
    }

    private PaletteSuggestionService() {}

    public static void suggestions(Minecraft mc, String input, Consumer<List<Item>> callback) {
        suggestions(mc, input, input == null ? 0 : input.length(), callback);
    }

    public static void suggestions(Minecraft mc, String input, int cursor, Consumer<List<Item>> callback) {
        String value = input == null ? "" : input;
        Token token = activeToken(value, cursor);

        if (token.text().startsWith("@")) {
            callback.accept(referenceSuggestions(mc, value, token));
            return;
        }

        if (token.text().startsWith("#")) {
            String name = token.text().substring(1);
            if (!name.isBlank()) {
                callback.accept(List.of(new Item(value, "#" + name + "  ·  designate here", Source.VOXELPILOT)));
                return;
            }
        }

        if (value.startsWith("/")) {
            commandSuggestions(mc, value, callback);
            return;
        }

        // "/" embedded in a sentence ("What does /trigger tpa do?") gets the same
        // server-backed completion as a standalone command, spliced back into the
        // surrounding text, as long as the cursor is still inside the command phrase.
        int embeddedStart = embeddedCommandStart(value, cursor);
        if (embeddedStart >= 0) {
            String prefix = value.substring(0, embeddedStart);
            String commandPhrase = value.substring(embeddedStart);
            commandSuggestions(mc, commandPhrase, items -> callback.accept(withPrefix(prefix, items)));
            return;
        }

        callback.accept(contextSuggestions(value));
    }

    // Once the sentence continues past the command ("... do? Also is @Steve online"),
    // Brigadier has no way to know where the command phrase ends, so this only fires
    // while the cursor is still at the end of an in-progress "/..." phrase.
    private static int embeddedCommandStart(String value, int cursor) {
        if (cursor != value.length()) return -1;
        int start = value.lastIndexOf('/');
        if (start <= 0) return -1;
        if (!Character.isWhitespace(value.charAt(start - 1))) return -1;
        return start;
    }

    private static List<Item> withPrefix(String prefix, List<Item> items) {
        if (prefix.isEmpty()) return items;
        return items.stream().map(item -> new Item(prefix + item.value(), item.label(), item.source())).toList();
    }

    private static void commandSuggestions(Minecraft mc, String input, Consumer<List<Item>> callback) {
        // VoxelPilot's own commands and recent usage are computed synchronously -- surface them
        // immediately so Tab/arrows are never waiting on a server round-trip just to see
        // "/wayfinder" or "/clear". Real server commands only exist via Brigadier's async
        // completion API, so those merge in a moment later, once resolved, as a second update.
        Map<String, Item> local = new LinkedHashMap<>();
        for (Item item : voxelPilotCommands(mc, input)) local.putIfAbsent(item.value(), item);
        for (Item item : CommandUsageStore.get().matching(input, 12)) local.putIfAbsent(item.value(), item);
        callback.accept(rank(local, input));

        if (mc.getConnection() == null) return;

        String command = input.substring(1);
        var dispatcher = mc.getConnection().getCommands();
        SharedSuggestionProvider source = mc.getConnection().getSuggestionsProvider();
        ParseResults<SharedSuggestionProvider> parsed = dispatcher.parse(command, source);

        dispatcher.getCompletionSuggestions(parsed).whenComplete((result, error) -> mc.execute(() -> {
            Map<String, Item> merged = new LinkedHashMap<>(local);
            if (error == null && result != null) {
                for (Suggestion suggestion : result.getList()) {
                    String completed = "/" + suggestion.apply(command);
                    merged.put(completed, new Item(completed, completed, Source.SERVER));
                }
            }
            callback.accept(rank(merged, input));
        }));
    }

    private static List<Item> rank(Map<String, Item> items, String input) {
        return items.values().stream()
            .sorted((a, b) -> Integer.compare(score(b, input), score(a, input)))
            .limit(8)
            .toList();
    }

    private static List<Item> voxelPilotCommands(Minecraft mc, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<Item> items = new ArrayList<>();
        if ("/shortcuts".startsWith(lower)) {
            items.add(new Item("/shortcuts", "/shortcuts  ·  keyboard shortcuts", Source.VOXELPILOT));
        }
        if ("/wayfinder".startsWith(lower) || lower.startsWith("/wayfinder")) {
            if (lower.startsWith("/wayfinder")) {
                String query = input.length() > 10 ? input.substring(10).trim() : "";
                // Real nearby matches first (selecting one jumps straight to that exact spot),
                // then registry-name completions to keep typing/refine the search text itself.
                for (WayfinderManager.PreviewMatch match : WayfinderManager.get().previewNearby(mc, query, 5)) {
                    String value = "/wayfinder at " + match.pos().getX() + "," + match.pos().getY() + "," + match.pos().getZ() + " " + match.name();
                    String label = match.name() + "  ·  " + RelativeBearing.deltaLabel(match.right(), match.forward(), match.up());
                    items.add(new Item(value, label, Source.PREVIEW));
                }
                for (WayfinderManager.Suggestion suggestion : WayfinderManager.get().suggestions(query, 8)) {
                    String value = "/wayfinder " + suggestion.id();
                    items.add(new Item(value, value, Source.VOXELPILOT));
                }
            } else {
                items.add(new Item("/wayfinder", "/wayfinder", Source.VOXELPILOT));
            }
        }
        if ("/wayfinder next".startsWith(lower)) {
            items.add(new Item("/wayfinder next", "/wayfinder next  ·  next search result", Source.VOXELPILOT));
        }
        if ("/clear".startsWith(lower)) {
            items.add(new Item("/clear", "/clear  ·  clear everything (selection, preview, wayfinder, pins, chat)", Source.VOXELPILOT));
        }
        if (lower.startsWith("/clear")) {
            String partial = lower.length() > 6 ? lower.substring(6).stripLeading() : "";
            for (String target : CLEAR_TARGETS) {
                if (target.startsWith(partial)) {
                    String value = "/clear " + target;
                    items.add(new Item(value, value + "  ·  clear " + target, Source.VOXELPILOT));
                }
            }
        }
        for (var entry : SIMPLE_COMMANDS.entrySet()) {
            if (entry.getKey().startsWith(lower)) {
                items.add(new Item(entry.getKey(), entry.getKey() + "  ·  " + entry.getValue(), Source.VOXELPILOT));
            }
        }
        if ("/speed".startsWith(lower)) {
            items.add(new Item("/speed", "/speed  ·  set build speed", Source.VOXELPILOT));
        }
        if (lower.startsWith("/speed")) {
            String partial = lower.length() > 6 ? lower.substring(6).stripLeading() : "";
            for (BuildSpeed speed : BuildSpeed.values()) {
                String name = speed.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(partial)) {
                    String value = "/speed " + name;
                    items.add(new Item(value, value, Source.VOXELPILOT));
                }
            }
        }
        return items;
    }

    private static List<Item> referenceSuggestions(Minecraft mc, String input, Token token) {
        String needle = token.text().substring(1);
        return ReferenceResolver.matching(mc, needle, 8).stream()
            .map(reference -> {
                String completed = input.substring(0, token.start()) + reference.token() + input.substring(token.end());
                return new Item(completed, reference.paletteLabel(), Source.REFERENCE);
            })
            .toList();
    }

    private static List<Item> contextSuggestions(String input) {
        if (!input.isBlank()) return List.of();
        return List.of(
            new Item("/", "/", Source.SERVER),
            new Item("@", "@", Source.REFERENCE),
            new Item("#", "#  ·  designate this location", Source.VOXELPILOT)
        );
    }

    private static int score(Item item, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        String value = item.value().toLowerCase(Locale.ROOT);
        int score = 0;
        if (value.equals(lower)) score += 1_000_000;
        else if (value.startsWith(lower)) score += 500_000;
        else if (fuzzyMatches(value, lower)) score += 100_000;
        else score += CommandUsageStore.get().score(item.value());
        // Real nearby matches beat plain registry-name completions for the same typed text --
        // finding an actual chest of redstone matters more than offering to type "redstone_ore".
        if (item.source() == Source.PREVIEW) score += 600_000;
        if (item.source() == Source.VOXELPILOT) score += 10_000;
        if (item.source() == Source.SERVER) score += 1_000;
        return score;
    }

    private static boolean fuzzyMatches(String text, String pattern) {
        int patternIdx = 0;
        for (int textIdx = 0; patternIdx < pattern.length() && textIdx < text.length(); textIdx++) {
            if (pattern.charAt(patternIdx) == text.charAt(textIdx)) {
                patternIdx++;
            }
        }
        return patternIdx == pattern.length();
    }

    private static Token activeToken(String input, int cursor) {
        int safeCursor = Math.max(0, Math.min(cursor, input.length()));
        int start = safeCursor;
        int end = safeCursor;
        while (start > 0 && !Character.isWhitespace(input.charAt(start - 1))) start--;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) end++;
        return new Token(start, end, input.substring(start, end));
    }

    private record Token(int start, int end, String text) {}
}
