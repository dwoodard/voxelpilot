package dev.dwoodard.voxelpilot.eval;

import dev.dwoodard.voxelpilot.ai.AiPlanner;
import dev.dwoodard.voxelpilot.ai.ChatMessage;
import dev.dwoodard.voxelpilot.ai.OpenAiCompatibleProvider;
import dev.dwoodard.voxelpilot.config.ProviderConfig;
import dev.dwoodard.voxelpilot.plan.Bounds;
import dev.dwoodard.voxelpilot.plan.LocalChange;
import dev.dwoodard.voxelpilot.plan.PlanException;
import dev.dwoodard.voxelpilot.plan.PlanNode;
import dev.dwoodard.voxelpilot.plan.PlanRenderer;
import dev.dwoodard.voxelpilot.plan.PlanScript;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// Offline model eval: streams fixed prompts with canned world context from an
// OpenAI-compatible endpoint, parses the script exactly like the game does, and checks the
// result with the real renderer. Reports time to the first previewable line and total time.
//
//   ./bootstrap.sh evalPlans -PevalModel=openai/gpt-oss-20b [-PevalUrl=http://127.0.0.1:1234/v1]
//
// Block ids and states are not checked here (that needs the game registry); everything else is.
public final class PlanEval {
    private record Case(String name, String prompt, String world, String currentScript, Function<Check, String> expect) {}

    private record Check(List<PlanNode> nodes, List<LocalChange> changes, String say) {
        long solid() { return changes.stream().filter(c -> !c.block().isAir()).count(); }
        long air() { return changes.stream().filter(c -> c.block().isAir()).count(); }
        boolean hasType(String type) { return nodes.stream().anyMatch(n -> type.equals(n.type)); }
        String within(List<LocalChange> subset, int w, int h, int d) {
            Bounds b = Bounds.of(subset);
            return b.minX() >= 0 && b.minY() >= 0 && b.minZ() >= 0 && b.maxX() < w && b.maxY() < h && b.maxZ() < d
                ? null : "outside selection: " + b;
        }
    }

    // Same shape WorldContextService produces for level ground at y=groundY.
    private static String world(int w, int h, int d, int groundY, String ground) {
        int area = w * d;
        String layers = (h - 1 > groundY ? "{\"y\":\"" + (groundY + 1) + "-" + (h - 1) + "\",\"blocks\":{\"air\":" + area + "}}," : "")
            + "{\"y\":\"" + (groundY == 0 ? "0" : "0-" + groundY) + "\",\"blocks\":{\"" + ground + "\":" + area + "}}";
        return "{\"gameMode\":\"creative\",\"selection\":[" + w + "," + h + "," + d + "],\"surface\":{\"flat\":" + groundY
            + ",\"blocks\":{\"" + ground + "\":" + area + "}},\"layers\":[" + layers + "],\"player\":{\"at\":[1," + (groundY + 1) + ",-4],\"insideSelection\":false}}";
    }

    private static final List<Case> CASES = List.of(
        new Case("platform", "make a one-block-high stone platform", world(3, 3, 3, 0, "grass_block"), null, c -> {
            long stone = c.changes().stream().filter(ch -> ch.block().id().equals("minecraft:stone")).count();
            return stone == 9 ? c.within(c.changes(), 3, 3, 3) : "expected 9 stone, got " + stone;
        }),
        new Case("revise-taller", "make it two blocks high", world(3, 4, 3, 0, "grass_block"),
            "platform = box 0 1 0 3 1 3 stone",
            c -> c.solid() == 18 ? null : "expected 18 blocks, got " + c.solid()),
        new Case("cabin", "build a small oak cabin with a door and a gable roof", world(7, 9, 9, 0, "grass_block"), null, c -> {
            if (!c.hasType("door")) return "no door";
            if (!c.hasType("roof")) return "no roof";
            return c.solid() < 60 ? "suspiciously small: " + c.solid() : null;
        }),
        new Case("stairs-down", "dig a staircase going down 10 blocks", world(1, 14, 12, 13, "stone"), null, c -> {
            List<LocalChange> steps = c.changes().stream().filter(ch -> ch.block().id().endsWith("_stairs")).toList();
            if (steps.size() < 10) return "expected >= 10 stair blocks, got " + steps.size();
            int top = steps.stream().mapToInt(LocalChange::y).max().orElse(0);
            int bottom = steps.stream().mapToInt(LocalChange::y).min().orElse(0);
            return top - bottom < 9 ? "only descends " + (top - bottom) : c.within(steps, 1, 14, 12);
        }),
        new Case("clear", "clear out everything in this area", world(5, 3, 5, 2, "stone"), null,
            c -> c.air() >= 50 ? null : "expected an air box, got " + c.air() + " air"),
        new Case("greeting", "hey, what can you do?", world(3, 2, 3, 0, "grass_block"), null,
            c -> c.nodes().isEmpty() && c.say() != null ? null : "should only say something")
    );

    public static void main(String[] args) {
        ProviderConfig config = new ProviderConfig();
        config.model = args.length > 0 && !args[0].isBlank() ? args[0] : "openai/gpt-oss-20b";
        if (args.length > 1 && !args[1].isBlank()) config.baseUrl = args[1];
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config);
        System.out.println("Model: " + config.model + " @ " + config.baseUrl);
        System.out.printf("%-14s %-5s %9s %8s  %s%n", "case", "", "1st line", "total", "");

        int passed = 0;
        for (Case c : CASES) {
            StringBuilder content = new StringBuilder("REQUEST: ").append(c.prompt()).append('\n');
            if (c.currentScript() != null) content.append("\nCURRENT SCRIPT:\n").append(c.currentScript()).append('\n');
            content.append("\nWORLD: ").append(c.world());
            List<ChatMessage> messages = List.of(new ChatMessage("system", AiPlanner.SYSTEM_PROMPT), new ChatMessage("user", content.toString()));

            List<PlanNode> nodes = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            String[] say = new String[1];
            long start = System.currentTimeMillis();
            long[] firstNode = {-1};
            String verdict;
            String script = "";
            try {
                script = provider.stream(messages, line -> {
                    try {
                        PlanScript.Line parsed = PlanScript.parse(line);
                        if (parsed == null) return;
                        if (parsed.say() != null) say[0] = parsed.say();
                        if (parsed.node() == null) return;
                        List<PlanNode> candidate = new ArrayList<>(nodes);
                        candidate.add(parsed.node());
                        PlanRenderer.render(candidate);
                        nodes.add(parsed.node());
                        if (firstNode[0] < 0) firstNode[0] = System.currentTimeMillis() - start;
                    } catch (PlanException e) {
                        skipped.add(line + " -> " + e.getMessage());
                    }
                }).join();
                List<LocalChange> changes = PlanRenderer.render(nodes).changes();
                String failure = c.expect().apply(new Check(nodes, changes, say[0]));
                verdict = failure == null ? "PASS" : "FAIL " + failure;
                if (failure == null) passed++;
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                verdict = "ERROR " + root.getMessage();
            }
            long total = System.currentTimeMillis() - start;
            System.out.printf("%-14s %-5s %9s %7dms  %s%n", c.name(), verdict.equals("PASS") ? "PASS" : "",
                firstNode[0] < 0 ? "-" : firstNode[0] + "ms", total, verdict.equals("PASS") ? "" : verdict);
            for (String s : skipped) System.out.println("               skipped: " + s);
            if (!verdict.equals("PASS") && !script.isBlank()) System.out.println("               " + script.replace("\n", "\n               "));
        }
        System.out.println(passed + "/" + CASES.size() + " passed");
        System.exit(0);
    }
}
