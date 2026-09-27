package dev.dwoodard.voxelpilot.ui;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
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
    public enum Source { SERVER, REFERENCE, VOXELPILOT, RECENT }

    public record Item(String value, String label, Source source) {}

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

        callback.accept(contextSuggestions(value));
    }

    private static void commandSuggestions(Minecraft mc, String input, Consumer<List<Item>> callback) {
        if (mc.getConnection() == null) {
            callback.accept(voxelPilotCommands(input));
            return;
        }

        String command = input.substring(1);
        var dispatcher = mc.getConnection().getCommands();
        SharedSuggestionProvider source = mc.getConnection().getSuggestionsProvider();
        ParseResults<SharedSuggestionProvider> parsed = dispatcher.parse(command, source);

        dispatcher.getCompletionSuggestions(parsed).whenComplete((result, error) -> mc.execute(() -> {
            Map<String, Item> merged = new LinkedHashMap<>();

            if (error == null && result != null) {
                for (Suggestion suggestion : result.getList()) {
                    String completed = "/" + suggestion.apply(command);
                    merged.put(completed, new Item(completed, completed, Source.SERVER));
                }
            }

            for (Item item : voxelPilotCommands(input)) merged.putIfAbsent(item.value(), item);
            for (Item item : CommandUsageStore.get().matching(input, 12)) merged.putIfAbsent(item.value(), item);

            List<Item> ranked = merged.values().stream()
                .sorted((a, b) -> Integer.compare(score(b, input), score(a, input)))
                .limit(8)
                .toList();
            callback.accept(ranked);
        }));
    }

    private static List<Item> voxelPilotCommands(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<Item> items = new ArrayList<>();
        if ("/shortcuts".startsWith(lower)) {
            items.add(new Item("/shortcuts", "/shortcuts  ·  keyboard shortcuts", Source.VOXELPILOT));
        }
        if ("/wayfinder next".startsWith(lower)) {
            items.add(new Item("/wayfinder next", "/wayfinder next  ·  next search result", Source.VOXELPILOT));
        }
        if (!lower.startsWith("/wayfinder next")
            && ("/wayfinder".startsWith(lower) || lower.startsWith("/wayfinder"))) {
            if (lower.startsWith("/wayfinder")) {
                String query = input.length() > 10 ? input.substring(10).trim() : "";
                for (WayfinderManager.Suggestion suggestion : WayfinderManager.get().suggestions(query, 8)) {
                    String value = "/wayfinder " + suggestion.id();
                    items.add(new Item(value, value, Source.VOXELPILOT));
                }
            } else {
                items.add(new Item("/wayfinder", "/wayfinder", Source.VOXELPILOT));
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
