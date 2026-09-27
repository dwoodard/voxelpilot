package dev.dwoodard.voxelpilot.reference;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Session-scoped named locations. Persistence can be added without changing reference consumers. */
public final class ReferenceStore {
    private static final ReferenceStore INSTANCE = new ReferenceStore();
    private final List<Place> places = new ArrayList<>();

    private ReferenceStore() {}

    public static ReferenceStore get() { return INSTANCE; }

    public synchronized Optional<Place> designate(Minecraft mc, String name) {
        if (mc == null || mc.player == null || mc.level == null || name == null || name.isBlank()) return Optional.empty();
        String clean = clean(name);
        if (clean.isBlank()) return Optional.empty();
        places.removeIf(place -> place.name().equalsIgnoreCase(clean));
        Place place = new Place(clean, mc.level.dimension().location().toString(),
            mc.player.blockPosition().immutable(), System.currentTimeMillis());
        places.add(place);
        return Optional.of(place);
    }

    public synchronized Optional<Place> find(String name) {
        if (name == null) return Optional.empty();
        return places.stream().filter(place -> place.name().equalsIgnoreCase(clean(name))).findFirst();
    }

    public synchronized List<Place> matching(String needle, int limit) {
        String query = needle == null ? "" : clean(needle).toLowerCase(Locale.ROOT);
        return places.stream()
            .filter(place -> query.isBlank() || place.name().toLowerCase(Locale.ROOT).startsWith(query))
            .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()))
            .limit(limit)
            .toList();
    }

    private static String clean(String name) {
        String value = name.trim();
        if (value.startsWith("@") || value.startsWith("#")) value = value.substring(1);
        return value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    public record Place(String name, String dimension, BlockPos position, long observedAt) {}
}
