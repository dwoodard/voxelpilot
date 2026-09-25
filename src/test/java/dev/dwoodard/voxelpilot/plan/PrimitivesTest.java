package dev.dwoodard.voxelpilot.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PrimitivesTest {
    private static List<LocalChange> run(String line) {
        return PlanRenderer.render(List.of(PlanScript.parse(line).node())).changes();
    }

    private static Set<String> cells(List<LocalChange> changes) {
        return changes.stream().map(c -> c.x() + "," + c.y() + "," + c.z()).collect(Collectors.toSet());
    }

    @Test
    void smallFilledCircleIsRoundNotDiamond() {
        // radius 2: 5x5 minus the 4 corners
        assertEquals(21, run("circle 0 0 0 2 stone filled").size());
    }

    @Test
    void ringIsSymmetricAndHollow() {
        var ring = run("circle 0 0 0 8 oak_planks");
        Set<String> cells = cells(ring);
        assertFalse(cells.contains("0,0,0"));
        for (LocalChange c : ring) {
            assertTrue(cells.contains(-c.x() + ",0," + c.z()));
            assertTrue(cells.contains(c.x() + ",0," + -c.z()));
            assertTrue(cells.contains(c.z() + ",0," + c.x()));
        }
    }

    @Test
    void ringHasNoGapsAroundTheEdge() {
        // every ring cell has at least two 8-connected ring neighbours
        Set<String> cells = cells(run("circle 0 0 0 12 oak_planks"));
        for (String cell : cells) {
            String[] p = cell.split(",");
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[2]), neighbours = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if ((dx != 0 || dz != 0) && cells.contains((x + dx) + ",0," + (z + dz))) neighbours++;
            assertTrue(neighbours >= 2, "gap at " + cell);
        }
    }

    @Test
    void halfCircleArcKeepsOnlyTheForwardHalf() {
        var half = run("circle 0 0 0 12 oak_planks arc 270 90");
        assertTrue(half.stream().allMatch(c -> c.z() >= 0));
        assertTrue(half.stream().anyMatch(c -> c.x() == -12 && c.z() == 0));
        assertTrue(half.stream().anyMatch(c -> c.x() == 12 && c.z() == 0));
        assertTrue(half.stream().anyMatch(c -> c.x() == 0 && c.z() == 12));
    }

    @Test
    void filledWithDifferentInterior() {
        var disc = run("circle 0 0 0 5 stone_bricks fill oak_planks");
        assertEquals("minecraft:oak_planks", disc.stream().filter(c -> c.x() == 0 && c.z() == 0).findFirst().orElseThrow().block().id());
        assertEquals("minecraft:stone_bricks", disc.stream().filter(c -> c.x() == 5 && c.z() == 0).findFirst().orElseThrow().block().id());
    }

    @Test
    void thickerRingsHaveMoreBlocks() {
        assertTrue(run("circle 0 0 0 10 stone thick 3").size() > run("circle 0 0 0 10 stone").size());
    }

    @Test
    void evenCircleCentersBetweenBlocks() {
        Set<String> cells = cells(run("circle 0 0 0 3 stone filled even"));
        assertTrue(cells.contains("-3,0,0") && cells.contains("2,0,0"));
        assertFalse(cells.contains("3,0,0"));
    }

    @Test
    void ovalStretchesAlongZ() {
        Set<String> cells = cells(run("circle 0 0 0 3 stone oval 8"));
        assertTrue(cells.contains("0,0,8"));
        assertFalse(cells.contains("8,0,0"));
    }

    @Test
    void squarenessFillsCorners() {
        assertTrue(run("circle 0 0 0 8 stone filled square 0.9").size() > run("circle 0 0 0 8 stone filled").size());
    }

    @Test
    void heightMakesARoundWall() {
        var tower = run("circle 0 0 0 4 stone height 5");
        assertEquals(5, tower.stream().mapToInt(LocalChange::y).distinct().count());
    }

    @Test
    void domeIsTopHalfOfSphere() {
        var dome = run("sphere 0 0 0 6 glass hollow dome");
        assertTrue(dome.stream().allMatch(c -> c.y() >= 0));
        assertFalse(cells(dome).contains("0,0,0"));
        assertTrue(cells(dome).contains("0,6,0"));
    }

    @Test
    void diagonalLineIsContinuous() {
        var line = run("line 0 0 0 5 5 5 fence");
        assertEquals(6, line.size());
        assertTrue(cells(line).contains("5,5,5"));
    }

    @Test
    void lineStartsAtItsFirstPoint() {
        assertTrue(cells(run("line 2 0 1 2 4 1 stone")).containsAll(Set.of("2,0,1", "2,4,1")));
    }

    @Test
    void pyramidStepsInEachLayer() {
        var pyramid = run("pyramid 0 0 0 5 5 sandstone");
        assertEquals(25 + 9 + 1, pyramid.size());
    }
}
