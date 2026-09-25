package dev.dwoodard.voxelpilot.build;

import dev.dwoodard.voxelpilot.plan.BlockSpec;
import dev.dwoodard.voxelpilot.plan.Dir;
import dev.dwoodard.voxelpilot.plan.PlanException;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// Turns a BlockSpec into a real BlockState using the game's own registry and state
// definitions. Every property must exist on the block and every value must be legal;
// nothing is silently dropped. Local directions (forward/back/left/right) are mapped
// through the frame, so the model never has to know which way is north.
public final class BlockSpecResolver {
    private static final Set<String> FORBIDDEN = Set.of(
        "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
        "minecraft:structure_block", "minecraft:jigsaw", "minecraft:barrier", "minecraft:light",
        "minecraft:structure_void", "minecraft:end_portal", "minecraft:nether_portal", "minecraft:end_gateway");

    private final Frame frame;
    private final Map<BlockSpec, BlockState> cache = new HashMap<>();
    private final List<String> notes = new ArrayList<>();

    public BlockSpecResolver(Frame frame) { this.frame = frame; }

    public List<String> notes() { return notes; }

    public BlockState resolve(BlockSpec spec) {
        BlockState cached = cache.get(spec);
        if (cached != null) return cached;
        BlockState state = resolveUncached(spec);
        cache.put(spec, state);
        return state;
    }

    private BlockState resolveUncached(BlockSpec spec) {
        String id = spec.id();
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            ResourceLocation corrected = nearestKnownBlock(id);
            if (corrected == null) throw new PlanException("Unknown block '" + id + "'");
            notes.add("Used " + corrected.getPath() + " for unknown block " + id.replace("minecraft:", ""));
            location = corrected;
        }
        if (FORBIDDEN.contains(location.toString())) throw new PlanException("Block '" + location + "' is not allowed");

        Block block = BuiltInRegistries.BLOCK.get(location);
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : spec.state().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                String valid = block.getStateDefinition().getProperties().stream().map(Property::getName).collect(Collectors.joining(", "));
                throw new PlanException(location.getPath() + " has no property '" + entry.getKey() + "'"
                    + (valid.isEmpty() ? " (it has no properties)" : " (it has: " + valid + ")"));
            }
            state = withValue(state, property, localToWorld(property, entry.getValue()), location);
        }
        return state;
    }

    private String localToWorld(Property<?> property, String value) {
        if (property.getValueClass() == Direction.class && Dir.isName(value)) {
            return frame.toWorld(Dir.parse(value, Dir.FORWARD)).getSerializedName();
        }
        if (property.getValueClass() == Direction.Axis.class) {
            if (value.equals("forward") || value.equals("back")) return frame.forward().getAxis().getSerializedName();
            if (value.equals("left") || value.equals("right")) return frame.right().getAxis().getSerializedName();
            if (value.equals("up") || value.equals("vertical")) return "y";
        }
        return value;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value, ResourceLocation id) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            String allowed = property.getPossibleValues().stream().map(property::getName).collect(Collectors.joining(", "));
            throw new PlanException("Invalid " + property.getName() + "=" + value + " for " + id.getPath() + " (allowed: " + allowed + ")");
        }
        return state.setValue(property, parsed.get());
    }

    // Models occasionally emit a near-miss id ("oak_planes"). Only a very close match is
    // accepted, and every correction is reported in the preview notes, never applied silently.
    private static ResourceLocation nearestKnownBlock(String badId) {
        String badPath = badId.contains(":") ? badId.substring(badId.indexOf(':') + 1) : badId;
        ResourceLocation best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ResourceLocation candidate : BuiltInRegistries.BLOCK.keySet()) {
            int distance = levenshtein(badPath, candidate.getPath());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= 2 ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[b.length()];
    }
}
