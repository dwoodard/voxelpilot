package dev.dwoodard.voxelpilot.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanScriptTest {
    private static List<LocalChange> run(String... lines) {
        List<PlanNode> nodes = new ArrayList<>();
        for (String line : lines) {
            PlanScript.Line parsed = PlanScript.parse(line);
            if (parsed != null && parsed.node() != null) nodes.add(parsed.node());
        }
        return PlanRenderer.render(nodes).changes();
    }

    @Test
    void cabinScriptRendersSameShapesAsComponents() {
        var changes = run(
            "title Spruce cabin",
            "floor = box 0 0 0 7 1 9 cobblestone",
            "walls = box on floor 0 0 0 7 3 9 spruce_planks walls",
            "door 3 1 0 back",
            "set 0 2 4 glass_pane",
            "roof on walls -1 0 -1 9 11 spruce_stairs ends spruce_planks",
            "say Spruce cabin with a door facing you.");
        assertTrue(changes.stream().anyMatch(c -> c.block().id().equals("minecraft:oak_door")));
        assertTrue(changes.stream().anyMatch(c -> c.y() == 4 && c.block().id().equals("minecraft:spruce_stairs")));
        assertTrue(changes.stream().anyMatch(c -> c.x() == 0 && c.y() == 2 && c.z() == 4 && c.block().id().equals("minecraft:glass_pane")));
    }

    @Test
    void parsesBoxWithFill() {
        PlanNode node = PlanScript.parse("box 1 2 3 4 5 6 stone hollow").node();
        assertArrayEquals(new int[]{1, 2, 3}, node.at);
        assertArrayEquals(new int[]{4, 5, 6}, node.size);
        assertEquals("hollow", node.fill);
    }

    @Test
    void parsesStairsWithOptions() {
        PlanNode node = PlanScript.parse("s = stairs 0 15 0 forward down 10 stone_brick_stairs width 2 nocarve").node();
        assertEquals("s", node.id);
        assertEquals("down", node.vertical);
        assertEquals(10, node.length);
        assertEquals(2, node.width);
        assertFalse(node.carve);
    }

    @Test
    void roofAcrossTurnsRidge() {
        assertEquals("left", PlanScript.parse("roof 0 0 0 5 7 oak_stairs across").node().direction);
    }

    @Test
    void keepsBlockStateBracketsTogether() {
        PlanNode node = PlanScript.parse("set 0 0 0 oak_log[axis=forward, stripped=true]").node();
        assertEquals("oak_log[axis=forward, stripped=true]", node.blocks.get(0).block);
    }

    @Test
    void toleratesDecorationsAndCommas() {
        assertNotNull(PlanScript.parse("1. box 0,0,0 3,1,3 stone").node());
        assertNotNull(PlanScript.parse("- `box 0 0 0 3 1 3 stone`").node());
        assertEquals("floor", PlanScript.parse("floor=box 0 0 0 3 1 3 stone").node().id);
        assertNull(PlanScript.parse("```"));
        assertNull(PlanScript.parse("# comment"));
        assertNull(PlanScript.parse("   "));
    }

    @Test
    void metaLinesKeepCase() {
        assertEquals("Hello There", PlanScript.parse("say Hello There").say());
        assertEquals("Big Barn", PlanScript.parse("title Big Barn").title());
        assertArrayEquals(new int[]{1, 2, 3}, PlanScript.parse("move 1 2 3").move());
    }

    @Test
    void blockNameAsCommandMeansSet() {
        PlanNode chest = PlanScript.parse("chest 0 2 1 chest[facing=right,type=single]").node();
        assertEquals("blocks", chest.type);
        assertArrayEquals(new int[]{0, 2, 1}, chest.at);
        assertEquals("chest[facing=right,type=single]", chest.blocks.get(0).block);
        assertEquals("hopper", PlanScript.parse("hopper 1 0 0").node().blocks.get(0).block);
    }

    @Test
    void stateAcceptsSpacesBetweenProperties() {
        PlanNode hopper = PlanScript.parse("set 1 0 0 hopper[enabled=true facing=left]").node();
        var changes = PlanRenderer.render(java.util.List.of(hopper)).changes();
        assertEquals(java.util.Map.of("enabled", "true", "facing", "left"), changes.get(0).block().state());
    }

    @Test
    void parsesSelect() {
        assertArrayEquals(new int[]{-1, 1, -1, 0, 1, -1}, PlanScript.parse("select -1 1 -1 0 1 -1").select());
        assertThrows(PlanException.class, () -> PlanScript.parse("select 0 0 0 1 1"));
    }

    @Test
    void errorsAreSpecific() {
        assertTrue(assertThrows(PlanException.class, () -> PlanScript.parse("box 0 0 0 3 1 stone")).getMessage().contains("number for depth"));
        assertTrue(assertThrows(PlanException.class, () -> PlanScript.parse("teapot please")).getMessage().contains("unknown command"));
        assertTrue(assertThrows(PlanException.class, () -> PlanScript.parse("box 0 0 0 3 1 3 stone glassy")).getMessage().contains("unknown option"));
        assertTrue(assertThrows(PlanException.class, () -> PlanScript.parse("door 0 0 0 back oak_door extra")).getMessage().contains("unexpected"));
    }
}
