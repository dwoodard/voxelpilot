package dev.dwoodard.voxelpilot.plan;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockSpecTest {
    @Test
    void addsNamespaceAndLowercases() {
        assertEquals("minecraft:stone", BlockSpec.parse(" Stone ").id());
    }

    @Test
    void parsesVanillaStateSyntax() {
        BlockSpec spec = BlockSpec.parse("minecraft:oak_stairs[facing=forward, half=top]");
        assertEquals("minecraft:oak_stairs", spec.id());
        assertEquals(Map.of("facing", "forward", "half", "top"), spec.state());
        assertEquals("minecraft:oak_stairs[facing=forward,half=top]", spec.toString());
    }

    @Test
    void rejectsMalformedState() {
        assertThrows(PlanException.class, () -> BlockSpec.parse("oak_stairs[facing]"));
        assertThrows(PlanException.class, () -> BlockSpec.parse("oak_stairs[facing=east"));
        assertThrows(PlanException.class, () -> BlockSpec.parse(""));
    }

    @Test
    void withDefaultKeepsExplicitValue() {
        BlockSpec spec = BlockSpec.parse("oak_stairs[half=top]").withDefault("half", "bottom");
        assertEquals("top", spec.state().get("half"));
    }

    @Test
    void recognizesAirVariants() {
        assertTrue(BlockSpec.parse("air").isAir());
        assertTrue(BlockSpec.parse("cave_air").isAir());
        assertFalse(BlockSpec.parse("stone").isAir());
    }
}
