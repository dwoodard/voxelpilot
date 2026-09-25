package dev.dwoodard.voxelpilot.plan;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlanRendererTest {
    private static final Gson GSON = new Gson();

    private static List<LocalChange> render(String nodesJson) {
        List<PlanNode> nodes = GSON.fromJson(nodesJson, new TypeToken<List<PlanNode>>() {}.getType());
        return PlanRenderer.render(nodes).changes();
    }

    private static Optional<LocalChange> at(List<LocalChange> changes, int x, int y, int z) {
        return changes.stream().filter(c -> c.x() == x && c.y() == y && c.z() == z).findFirst();
    }

    @Test
    void solidBoxFillsEveryBlock() {
        var changes = render("[{\"id\":\"p\",\"type\":\"box\",\"size\":[3,1,3],\"block\":\"stone\"}]");
        assertEquals(9, changes.size());
        assertTrue(changes.stream().allMatch(c -> c.block().id().equals("minecraft:stone")));
    }

    @Test
    void hollowBoxClearsInterior() {
        var changes = render("[{\"type\":\"box\",\"size\":[3,3,3],\"fill\":\"hollow\",\"block\":\"stone\"}]");
        assertEquals(27, changes.size());
        assertTrue(at(changes, 1, 1, 1).orElseThrow().block().isAir());
        assertEquals(26, changes.stream().filter(c -> !c.block().isAir()).count());
    }

    @Test
    void wallsLeaveInteriorUntouched() {
        var changes = render("[{\"type\":\"box\",\"size\":[5,2,4],\"fill\":\"walls\",\"block\":\"stone\"}]");
        // perimeter of 5x4 is 14 columns, 2 high
        assertEquals(28, changes.size());
        assertTrue(at(changes, 2, 0, 1).isEmpty());
    }

    @Test
    void outlineIsEdgesOnly() {
        var changes = render("[{\"type\":\"box\",\"size\":[3,3,3],\"fill\":\"outline\",\"block\":\"stone\"}]");
        // 8 corners + 12 edge midpoints
        assertEquals(20, changes.size());
    }

    @Test
    void stairsUpFaceTheirDirection() {
        var changes = render("[{\"type\":\"stairs\",\"direction\":\"right\",\"length\":3,\"block\":\"oak_stairs\"}]");
        assertEquals(3, changes.size());
        LocalChange top = at(changes, 2, 2, 0).orElseThrow();
        assertEquals("right", top.block().state().get("facing"));
        assertEquals("bottom", top.block().state().get("half"));
    }

    @Test
    void stairsDownFaceBackAndCarveHeadroom() {
        var changes = render("[{\"type\":\"stairs\",\"direction\":\"forward\",\"length\":4,\"vertical\":\"down\",\"block\":\"stone_stairs\"}]");
        LocalChange last = at(changes, 0, -3, 3).orElseThrow();
        assertEquals("back", last.block().state().get("facing"));
        for (int h = 1; h <= 3; h++) assertTrue(at(changes, 0, -3 + h, 3).orElseThrow().block().isAir());
        assertEquals(16, changes.size());
    }

    @Test
    void oddRoofHasSlabRidgeAndFacingSlopes() {
        var changes = render("[{\"type\":\"roof\",\"size\":[5,1,2],\"block\":\"spruce_stairs\"}]");
        // layers: x=0/4, x=1/3, ridge x=2 -> 5 per row, 2 rows
        assertEquals(10, changes.size());
        assertEquals("right", at(changes, 0, 0, 0).orElseThrow().block().state().get("facing"));
        assertEquals("left", at(changes, 4, 0, 0).orElseThrow().block().state().get("facing"));
        LocalChange ridge = at(changes, 2, 2, 1).orElseThrow();
        assertEquals("minecraft:spruce_slab", ridge.block().id());
        assertEquals("bottom", ridge.block().state().get("type"));
    }

    @Test
    void roofAcrossRunsRidgeLeftToRight() {
        var changes = render("[{\"type\":\"roof\",\"size\":[3,1,4],\"direction\":\"left\",\"block\":\"oak_stairs\"}]");
        assertEquals("forward", at(changes, 0, 0, 0).orElseThrow().block().state().get("facing"));
        assertEquals("back", at(changes, 0, 0, 3).orElseThrow().block().state().get("facing"));
        assertEquals("forward", at(changes, 2, 1, 1).orElseThrow().block().state().get("facing"));
    }

    @Test
    void roofEndsFillGables() {
        var changes = render("[{\"type\":\"roof\",\"size\":[5,1,3],\"block\":\"oak_stairs\",\"ends\":\"oak_planks\"}]");
        assertEquals("minecraft:oak_planks", at(changes, 2, 0, 0).orElseThrow().block().id());
        assertEquals("minecraft:oak_planks", at(changes, 2, 0, 2).orElseThrow().block().id());
        assertTrue(at(changes, 2, 0, 1).isEmpty());
    }

    @Test
    void doorHasBothHalves() {
        var changes = render("[{\"type\":\"door\",\"direction\":\"back\",\"at\":[2,1,0]}]");
        assertEquals("lower", at(changes, 2, 1, 0).orElseThrow().block().state().get("half"));
        assertEquals("upper", at(changes, 2, 2, 0).orElseThrow().block().state().get("half"));
        assertEquals("back", at(changes, 2, 2, 0).orElseThrow().block().state().get("facing"));
    }

    @Test
    void onStacksAboveParentSoTallerWallsLiftTheRoof() {
        String plan = "[{\"id\":\"walls\",\"type\":\"box\",\"at\":[0,1,0],\"size\":[5,%d,5],\"fill\":\"walls\",\"block\":\"stone\"},"
            + "{\"id\":\"roof\",\"type\":\"roof\",\"on\":\"walls\",\"at\":[-1,0,-1],\"size\":[7,1,7],\"block\":\"oak_stairs\"}]";
        assertTrue(at(render(plan.formatted(3)), -1, 4, -1).isPresent());
        assertTrue(at(render(plan.formatted(5)), -1, 6, -1).isPresent());
    }

    @Test
    void laterComponentsOverwriteEarlierOnes() {
        var changes = render("[{\"type\":\"box\",\"size\":[3,3,1],\"block\":\"stone\"},"
            + "{\"type\":\"box\",\"at\":[1,1,0],\"size\":[1,1,1],\"block\":\"glass_pane\"}]");
        assertEquals(9, changes.size());
        assertEquals("minecraft:glass_pane", at(changes, 1, 1, 0).orElseThrow().block().id());
    }

    @Test
    void rawBlocksCarryExplicitState() {
        var changes = render("[{\"type\":\"blocks\",\"at\":[1,0,0],\"blocks\":[{\"at\":[0,2,0],\"block\":\"lantern[hanging=true]\"}]}]");
        LocalChange lantern = at(changes, 1, 2, 0).orElseThrow();
        assertEquals(Map.of("hanging", "true"), lantern.block().state());
    }

    @Test
    void errorsNameTheComponent() {
        var e = assertThrows(PlanException.class, () -> render("[{\"id\":\"base\",\"type\":\"pyramid\"}]"));
        assertTrue(e.getMessage().contains("'base'"));
        assertTrue(e.getMessage().contains("unknown type"));
    }

    @Test
    void rejectsUnknownOnReference() {
        var e = assertThrows(PlanException.class, () -> render("[{\"type\":\"box\",\"on\":\"walls\",\"size\":[1,1,1],\"block\":\"stone\"}]"));
        assertTrue(e.getMessage().contains("not an earlier component"));
    }

    @Test
    void rejectsOversizedBox() {
        assertThrows(PlanException.class, () -> render("[{\"type\":\"box\",\"size\":[500,1,1],\"block\":\"stone\"}]"));
    }

    @Test
    void rejectsDuplicateIds() {
        assertThrows(PlanException.class, () -> render("[{\"id\":\"a\",\"type\":\"box\",\"size\":[1,1,1],\"block\":\"stone\"},"
            + "{\"id\":\"a\",\"type\":\"box\",\"size\":[1,1,1],\"block\":\"stone\"}]"));
    }

    @Test
    void emptyPlanRendersNothing() {
        assertTrue(PlanRenderer.render(List.of()).changes().isEmpty());
    }
}
