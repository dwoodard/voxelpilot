package dev.dwoodard.voxelpilot.eval;

import dev.dwoodard.voxelpilot.ai.AiPlanner;
import dev.dwoodard.voxelpilot.ai.BuildPlan;
import dev.dwoodard.voxelpilot.ai.ChatMessage;
import dev.dwoodard.voxelpilot.ai.OpenAiCompatibleProvider;
import dev.dwoodard.voxelpilot.ai.PlanJson;
import dev.dwoodard.voxelpilot.config.ProviderConfig;
import dev.dwoodard.voxelpilot.plan.Bounds;
import dev.dwoodard.voxelpilot.plan.LocalChange;
import dev.dwoodard.voxelpilot.plan.PlanException;
import dev.dwoodard.voxelpilot.plan.PlanRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// Offline model eval: sends fixed prompts with canned world context to an OpenAI-compatible
// endpoint and checks the returned plans with the real renderer. No Minecraft needed.
//
//   ./gradlew evalPlans -PevalModel=qwen/qwen3.5-9b [-PevalUrl=http://127.0.0.1:1234/v1]
//
// Block ids and states are not checked here (that needs the game registry); everything else is.
public final class PlanEval {
    private record Case(String name, String prompt, String context, String currentPlan, Function<Check, String> expect) {}

    private record Check(BuildPlan plan, List<LocalChange> changes) {
        long solid() { return changes.stream().filter(c -> !c.block().isAir()).count(); }
        long air() { return changes.stream().filter(c -> c.block().isAir()).count(); }
        boolean hasType(String type) { return plan.nodes.stream().anyMatch(n -> type.equals(n.type)); }
        Bounds bounds() { return Bounds.of(changes); }
        String within(int w, int h, int d) {
            Bounds b = bounds();
            return b.minX() >= 0 && b.minY() >= 0 && b.minZ() >= 0 && b.maxX() < w && b.maxY() < h && b.maxZ() < d
                ? null : "outside selection: " + b;
        }
    }

    private static String flatGround(int w, int h, int d, String block) {
        StringBuilder rows = new StringBuilder("[");
        for (int z = 0; z < d; z++) {
            rows.append(z == 0 ? "" : ",").append("[");
            for (int x = 0; x < w; x++) rows.append(x == 0 ? "" : ",").append(0);
            rows.append("]");
        }
        rows.append("]");
        StringBuilder layers = new StringBuilder("[");
        for (int y = h - 1; y >= 0; y--) {
            layers.append(y == h - 1 ? "" : ",").append("{\"y\":").append(y).append(",\"blocks\":{\"")
                .append(y == 0 ? block : "air").append("\":").append(w * d).append("}}");
        }
        layers.append("]");
        return "{\"gameMode\":\"creative\",\"selection\":[" + w + "," + h + "," + d + "],\"surface\":{\"from\":[0,0],\"stride\":1,\"heights\":"
            + rows + ",\"blocks\":{\"" + block + "\":" + (w * d) + "}},\"layers\":" + layers
            + ",\"player\":{\"at\":[1,1,-4],\"insideSelection\":false}}";
    }

    // A dig-down selection: J on the surface, then Option+Down. Ground is the top layer and
    // everything below it is solid.
    private static String solidGround(int w, int h, int d, String block) {
        return flatGround(w, h, d, block)
            .replaceAll("\\[(0,)*0\\]", "[" + String.join(",", java.util.Collections.nCopies(w, String.valueOf(h - 1))) + "]")
            .replace("\"air\":" + (w * d), "\"" + block + "\":" + (w * d));
    }

    private static final List<Case> CASES = List.of(
        new Case("platform", "make a one-block-high stone platform", flatGround(3, 2, 3, "grass_block"), null, c -> {
            if (c.solid() != 9) return "expected 9 stone blocks, got " + c.solid();
            return c.within(3, 2, 3);
        }),
        new Case("revise-taller", "make it two blocks high", flatGround(3, 3, 3, "grass_block"),
            "{\"title\":\"Stone platform\",\"message\":\"\",\"nodes\":[{\"id\":\"platform\",\"type\":\"box\",\"at\":[0,1,0],\"size\":[3,1,3],\"block\":\"stone\"}]}",
            c -> {
                if (c.solid() != 18) return "expected 18 blocks, got " + c.solid();
                return c.bounds().maxY() - c.bounds().minY() == 1 ? null : "expected 2 layers, got " + c.bounds();
            }),
        new Case("cabin", "build a small oak cabin with a door and a gable roof", flatGround(7, 8, 9, "grass_block"), null, c -> {
            if (!c.hasType("door")) return "no door";
            if (!c.hasType("roof")) return "no roof";
            return c.solid() < 60 ? "suspiciously small: " + c.solid() : null;
        }),
        new Case("stairs-down", "dig a staircase going down 10 blocks", solidGround(1, 14, 12, "stone"), null, c -> {
            if (!c.hasType("stairs")) return "no stairs component";
            List<LocalChange> steps = c.changes().stream().filter(ch -> ch.block().id().endsWith("_stairs")).toList();
            if (steps.size() < 10) return "expected >= 10 stair blocks, got " + steps.size();
            int top = steps.stream().mapToInt(LocalChange::y).max().orElse(0);
            int bottom = steps.stream().mapToInt(LocalChange::y).min().orElse(0);
            if (top - bottom < 9) return "only descends " + (top - bottom);
            // Steps must be inside the selection; carved headroom above the surface is air
            // over air and gets skipped in-game, so it isn't checked.
            return new Check(c.plan(), steps).within(1, 14, 12);
        }),
        new Case("clear", "clear out everything in this area", flatGround(5, 3, 5, "stone").replace("\"air\":25", "\"stone\":25"), null, c -> {
            if (c.air() < 25) return "expected an air box, got " + c.air() + " air";
            return c.within(5, 3, 5);
        }),
        new Case("greeting", "hey, what can you do?", flatGround(3, 2, 3, "grass_block"), null,
            c -> c.plan().nodes.isEmpty() ? null : "should not build for a greeting")
    );

    public static void main(String[] args) {
        ProviderConfig config = new ProviderConfig();
        config.model = args.length > 0 && !args[0].isBlank() ? args[0] : "qwen/qwen3.5-9b";
        if (args.length > 1 && !args[1].isBlank()) config.baseUrl = args[1];
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config);
        System.out.println("Model: " + config.model + " @ " + config.baseUrl);

        int passed = 0;
        for (Case c : CASES) {
            StringBuilder content = new StringBuilder("REQUEST: ").append(c.prompt()).append('\n');
            if (c.currentPlan() != null) content.append("\nCURRENT PLAN:\n").append(c.currentPlan()).append('\n');
            content.append("\nWORLD CONTEXT:\n").append(c.context());
            List<ChatMessage> messages = List.of(new ChatMessage("system", AiPlanner.SYSTEM_PROMPT), new ChatMessage("user", content.toString()));

            long start = System.currentTimeMillis();
            String verdict;
            String detail = "";
            String repaired = "";
            try {
                BuildPlan plan = provider.plan(messages).join();
                List<LocalChange> changes;
                try {
                    changes = PlanRenderer.render(plan.nodes).changes();
                } catch (PlanException e) {
                    // Same single repair round the game does (AiPlanner.attempt).
                    repaired = " (repaired: " + e.getMessage() + ")";
                    List<ChatMessage> retry = new ArrayList<>(messages);
                    retry.add(new ChatMessage("assistant", PlanJson.toJson(plan)));
                    retry.add(new ChatMessage("user", "The mod rejected that plan: " + e.getMessage() + "\nReturn the complete corrected plan JSON."));
                    plan = provider.plan(retry).join();
                    changes = PlanRenderer.render(plan.nodes).changes();
                }
                String failure = c.expect().apply(new Check(plan, changes));
                verdict = (failure == null ? "PASS" : "FAIL " + failure) + repaired;
                detail = plan.nodes.size() + " nodes, " + changes.size() + " changes · " + PlanJson.toJson(plan.nodes);
                if (failure == null) passed++;
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                verdict = "ERROR " + root.getMessage();
            }
            long ms = System.currentTimeMillis() - start;
            System.out.printf("%-14s %-6s %6dms  %s%n", c.name(), verdict.startsWith("PASS") ? "PASS" : "", ms, verdict.startsWith("PASS") ? verdict.substring(4).trim() : verdict);
            if (!detail.isEmpty()) System.out.println("               " + (detail.length() > 400 ? detail.substring(0, 400) + "…" : detail));
        }
        System.out.println(passed + "/" + CASES.size() + " passed");
        System.exit(0);
    }
}
