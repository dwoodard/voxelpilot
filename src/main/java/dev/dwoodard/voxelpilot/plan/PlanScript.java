package dev.dwoodard.voxelpilot.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// The build script the model writes: one command per line, each mapping 1:1 onto a
// component. Line-based so it can be parsed and previewed while the model is still
// streaming, and terse so a small local model emits it in a fraction of the tokens JSON
// takes. Examples:
//
//   title Spruce cabin
//   floor = box 0 0 0 7 1 9 cobblestone
//   walls = box on floor 0 0 0 7 3 9 spruce_planks walls
//   door 3 1 0 back
//   set 0 2 4 glass_pane
//   roof on walls -1 0 -1 9 11 spruce_stairs ends spruce_planks
//   stairs 0 15 0 forward down 10 stone_brick_stairs width 2
//   say Spruce cabin with a door facing you.
public final class PlanScript {
    public static final String COMMANDS = "box, stairs, roof, door, set, circle, sphere, line, pyramid, title, say, move";
    private static final Set<String> FILLS = Set.of("solid", "hollow", "walls", "outline");

    // Exactly one of node / title / say / move is set.
    public record Line(PlanNode node, String title, String say, int[] move) {}

    private PlanScript() {}

    // null for blank lines, comments, and markdown fences.
    public static Line parse(String raw) {
        String text = clean(raw);
        if (text.isEmpty()) return null;

        List<String> tokens = tokenize(text);
        String id = null;
        int start = 0;
        if (tokens.size() >= 3 && tokens.get(1).equals("=")) {
            id = tokens.get(0);
            start = 2;
        }
        String command = tokens.get(start).toLowerCase(Locale.ROOT);
        switch (command) {
            case "title" -> { return new Line(null, rest(text, command), null, null); }
            case "say" -> { return new Line(null, null, rest(text, command), null); }
        }

        Args args = new Args(tokens, start + 1, command);
        if (command.equals("move")) return new Line(null, null, null, new int[]{args.integer("x"), args.integer("y"), args.integer("z")});

        PlanNode node = new PlanNode();
        node.id = id;
        if (args.peekIs("on")) {
            args.next("on");
            node.on = args.next("name after 'on'");
        }
        node.at = new int[]{args.integer("x"), args.integer("y"), args.integer("z")};
        switch (command) {
            case "box" -> {
                node.type = "box";
                node.size = new int[]{args.integer("width"), args.integer("height"), args.integer("depth")};
                node.block = args.next("block");
                while (args.hasNext()) {
                    String option = args.next("option").toLowerCase(Locale.ROOT);
                    if (!FILLS.contains(option)) throw new PlanException("box: unknown option '" + option + "' (use hollow, walls, outline)");
                    node.fill = option;
                }
            }
            case "stairs" -> {
                node.type = "stairs";
                node.direction = args.next("direction");
                node.vertical = args.next("up or down");
                node.length = args.integer("length");
                node.block = args.next("block");
                while (args.hasNext()) {
                    String option = args.next("option").toLowerCase(Locale.ROOT);
                    switch (option) {
                        case "width" -> node.width = args.integer("width");
                        case "carve" -> node.carve = true;
                        case "nocarve" -> node.carve = false;
                        default -> throw new PlanException("stairs: unknown option '" + option + "' (use width N, carve, nocarve)");
                    }
                }
            }
            case "roof" -> {
                node.type = "roof";
                int w = args.integer("width"), d = args.integer("depth");
                node.size = new int[]{w, 1, d};
                node.block = args.next("block");
                while (args.hasNext()) {
                    String option = args.next("option").toLowerCase(Locale.ROOT);
                    switch (option) {
                        case "ends" -> node.ends = args.next("block after 'ends'");
                        case "ridge" -> node.ridge = args.next("block after 'ridge'");
                        case "across" -> node.direction = "left";
                        case "along" -> node.direction = "forward";
                        default -> throw new PlanException("roof: unknown option '" + option + "' (use ends BLOCK, ridge BLOCK, across)");
                    }
                }
            }
            case "door" -> {
                node.type = "door";
                node.direction = args.next("direction");
                if (args.hasNext()) node.block = args.next("block");
            }
            case "set" -> {
                node.type = "blocks";
                PlanNode.RawBlock raw1 = new PlanNode.RawBlock();
                raw1.at = new int[]{0, 0, 0};
                raw1.block = args.next("block");
                node.blocks = List.of(raw1);
            }
            case "circle" -> {
                node.type = "circle";
                node.radius = args.integer("radius");
                node.block = args.next("block");
                while (args.hasNext()) {
                    String option = args.next("option").toLowerCase(Locale.ROOT);
                    switch (option) {
                        case "filled" -> node.filled = true;
                        case "fill" -> node.fillBlock = args.next("block after 'fill'");
                        case "thick" -> node.thickness = args.integer("thickness");
                        case "height" -> node.height = args.integer("height");
                        case "oval" -> node.radiusZ = args.integer("oval radius");
                        case "even" -> node.even = true;
                        case "arc" -> { node.arcStart = args.integer("arc start"); node.arcEnd = args.integer("arc end"); }
                        case "square" -> node.squareness = args.decimal("squareness");
                        default -> throw new PlanException("circle: unknown option '" + option
                            + "' (use filled, fill BLOCK, thick N, height N, oval R, even, arc A B, square S)");
                    }
                }
            }
            case "sphere" -> {
                node.type = "sphere";
                node.radius = args.integer("radius");
                node.block = args.next("block");
                while (args.hasNext()) {
                    String option = args.next("option").toLowerCase(Locale.ROOT);
                    switch (option) {
                        case "hollow" -> node.fill = "hollow";
                        case "dome" -> node.dome = true;
                        default -> throw new PlanException("sphere: unknown option '" + option + "' (use hollow, dome)");
                    }
                }
            }
            case "line" -> {
                node.type = "line";
                node.to = new int[]{args.integer("end x"), args.integer("end y"), args.integer("end z")};
                node.block = args.next("block");
            }
            case "pyramid" -> {
                node.type = "pyramid";
                int w = args.integer("width"), d = args.integer("depth");
                node.size = new int[]{w, 1, d};
                node.block = args.next("block");
                if (args.hasNext()) {
                    String option = args.next("option").toLowerCase(Locale.ROOT);
                    if (!option.equals("hollow")) throw new PlanException("pyramid: unknown option '" + option + "' (use hollow)");
                    node.fill = "hollow";
                }
            }
            default -> throw new PlanException("unknown command '" + command + "' (use " + COMMANDS + ")");
        }
        if (args.hasNext()) throw new PlanException(command + ": unexpected '" + args.next("") + "'");
        return new Line(node, null, null, null);
    }

    // Tolerate the usual small-model decorations: fences, bullets, numbering, backticks.
    private static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```") || s.startsWith("#") || s.startsWith("//")) return "";
        s = s.replace("`", "");
        if (s.startsWith("- ") || s.startsWith("* ")) s = s.substring(2).trim();
        s = s.replaceFirst("^\\d+[.)]\\s+", "");
        return s.trim();
    }

    private static String rest(String text, String command) {
        int at = text.toLowerCase(Locale.ROOT).indexOf(command);
        return text.substring(at + command.length()).trim();
    }

    // Whitespace (and commas, outside block-state brackets) separate tokens; "name=box" is
    // split so the "=" is its own token.
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '[') depth++;
            if (c == ']') depth = Math.max(0, depth - 1);
            boolean separator = depth == 0 && (Character.isWhitespace(c) || c == ',');
            if (depth == 0 && c == '=' && out.isEmpty()) {
                if (!current.isEmpty()) { out.add(current.toString()); current.setLength(0); }
                out.add("=");
                continue;
            }
            if (separator) {
                if (!current.isEmpty()) { out.add(current.toString()); current.setLength(0); }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }

    private static final class Args {
        private final List<String> tokens;
        private final String command;
        private int index;

        Args(List<String> tokens, int index, String command) { this.tokens = tokens; this.index = index; this.command = command; }

        boolean hasNext() { return index < tokens.size(); }
        boolean peekIs(String value) { return hasNext() && tokens.get(index).equalsIgnoreCase(value); }

        String next(String what) {
            if (!hasNext()) throw new PlanException(command + ": missing " + what);
            return tokens.get(index++);
        }

        double decimal(String what) {
            String token = next(what);
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw new PlanException(command + ": expected a number for " + what + ", got '" + token + "'");
            }
        }

        int integer(String what) {
            String token = next(what);
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new PlanException(command + ": expected a number for " + what + ", got '" + token + "'");
            }
        }
    }
}
