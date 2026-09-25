package dev.dwoodard.voxelpilot.world;

import dev.dwoodard.voxelpilot.VoxelPilot;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Every block the running game knows (vanilla and mods), with its real state properties,
// read live from the registry. The model never sees the whole list: each request gets only
// the blocks its words point to, so it writes valid ids and states instead of guessing.
public final class BlockCatalog {
    public record Entry(String id, String description) {}

    // Plain-language words that don't appear in block ids.
    private static final Map<String, List<String>> SYNONYMS = Map.of(
        "wood", List.of("planks", "log"),
        "wooden", List.of("planks", "log"),
        "light", List.of("lantern", "torch", "lamp"),
        "lights", List.of("lantern", "torch", "lamp"),
        "window", List.of("glass", "pane"),
        "windows", List.of("glass", "pane"),
        "brick", List.of("bricks"),
        "path", List.of("path", "gravel"),
        "roof", List.of("stairs", "slab"),
        "fence", List.of("fence"));

    // Request words that would otherwise match block ids by accident ("all" -> wall).
    private static final Set<String> STOPWORDS = Set.copyOf(List.of(
        "the", "and", "all", "not", "fix", "point", "hook", "hooked", "onto", "with", "this", "that", "out", "for", "make", "build", "put", "from",
        "into", "some", "fill", "clear", "add", "set", "here", "there", "them", "its",
        "want", "can", "you", "your", "space", "area", "thing", "things", "big", "small", "little",
        "box", "circle", "sphere", "line", "top", "bottom", "side", "sides", "high", "tall", "wide",
        "long", "deep", "down", "left", "right", "forward", "back", "ok", "okay", "please", "blocks", "block"));

    private static List<Entry> all;

    private BlockCatalog() {}

    public static synchronized List<Entry> all() {
        if (all != null) return all;
        List<Entry> entries = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            if (block.defaultBlockState().isAir() && !key.getPath().equals("air")) continue;
            entries.add(new Entry(key.toString(), describe(key, block)));
        }
        entries.sort(Comparator.comparing(Entry::id));
        all = List.copyOf(entries);
        dump();
        return all;
    }

    // Blocks relevant to the request, best matches first. Vanilla wins ties; short ids win
    // ties among those (oak_planks before oak_planks_something_modded).
    public static List<Entry> search(String text, int limit) {
        Set<String> words = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_:]+")) {
            if (raw.length() < 3 || STOPWORDS.contains(raw)) continue;
            String word = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            words.add(word);
            if (word.endsWith("s") && word.length() > 4) words.add(word.substring(0, word.length() - 1));
            words.addAll(SYNONYMS.getOrDefault(word, List.of()));
        }
        if (words.isEmpty()) return List.of();

        Map<Entry, Integer> scores = new HashMap<>();
        for (Entry entry : all()) {
            String path = entry.id().substring(entry.id().indexOf(':') + 1);
            int score = 0;
            for (String word : words) {
                if (path.equals(word)) score += 100;
                else if (path.startsWith(word + "_") || path.endsWith("_" + word)) score += 40;
                else if (path.contains(word)) score += 15;
            }
            if (score > 0) scores.put(entry, score);
        }
        return scores.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Entry, Integer>>comparingInt(e -> -e.getValue())
                .thenComparing(e -> e.getKey().id().startsWith("minecraft:") ? 0 : 1)
                .thenComparingInt(e -> e.getKey().id().length()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    // "piston: facing=forward|back|left|right|up|down extended=true|false". Directions are
    // shown in the plan's local terms, which is what the model must write.
    private static String describe(ResourceLocation key, Block block) {
        String name = key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
        var properties = block.getStateDefinition().getProperties();
        if (properties.isEmpty()) return name;
        return name + ": " + properties.stream().map(BlockCatalog::describe).collect(Collectors.joining(" "));
    }

    private static <T extends Comparable<T>> String describe(Property<T> property) {
        String values;
        if (property.getValueClass() == Direction.class) {
            Set<String> local = new LinkedHashSet<>();
            for (T value : property.getPossibleValues()) {
                Direction d = (Direction) value;
                if (d.getAxis().isHorizontal()) local.addAll(List.of("forward", "back", "left", "right"));
                else local.add(d.getSerializedName());
            }
            values = String.join("|", local);
        } else if (property.getValueClass() == Direction.Axis.class) {
            values = property.getPossibleValues().stream()
                .map(v -> switch (((Direction.Axis) v)) { case Y -> "up"; case X, Z -> "forward|right"; })
                .distinct().collect(Collectors.joining("|"));
        } else if (property instanceof IntegerProperty ints) {
            var range = ints.getPossibleValues();
            values = range.stream().min(Integer::compare).orElse(0) + ".." + range.stream().max(Integer::compare).orElse(0);
        } else {
            values = property.getPossibleValues().stream().map(property::getName).collect(Collectors.joining("|"));
        }
        return property.getName() + "=" + values;
    }

    private static void dump() {
        try {
            Files.write(FMLPaths.CONFIGDIR.get().resolve("voxelpilot-blocks.txt"), all.stream().map(Entry::description).toList());
        } catch (IOException e) {
            VoxelPilot.LOGGER.warn("VoxelPilot: couldn't write block catalog", e);
        }
    }
}
