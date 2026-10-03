package dev.dwoodard.voxelpilot.ai;

import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts factual server-command context from natural-language prompts without executing it. */
public final class ServerCommandGrounding {
    private static final Pattern COMMAND = Pattern.compile("(?<!\\S)/([A-Za-z0-9_:.\\-]+)");

    private ServerCommandGrounding() {}

    public static String describe(Minecraft mc, String input) {
        if (mc == null || mc.getConnection() == null || input == null) return "";
        Matcher matcher = COMMAND.matcher(input);
        if (!matcher.find()) return "";

        String name = matcher.group(1);
        var dispatcher = mc.getConnection().getCommands();
        CommandNode<SharedSuggestionProvider> node = dispatcher.getRoot().getChild(name);
        if (node == null) return "Referenced server command /" + name + ": UNKNOWN (not present in current command tree)";

        Map<CommandNode<SharedSuggestionProvider>, String> usage =
            dispatcher.getSmartUsage(node, mc.getConnection().getSuggestionsProvider());
        StringBuilder out = new StringBuilder("Referenced server command /").append(name)
            .append(": PRESENT in current server command tree");
        if (!usage.isEmpty()) {
            out.append("\nKnown syntax:");
            usage.values().stream().distinct().limit(8).forEach(value -> out.append("\n  /").append(name).append(" ").append(value));
        }
        return out.toString();
    }
}
