package dev.dwoodard.voxelpilot.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

// A block plus optional state, written in vanilla command syntax the models already know:
// "oak_stairs[facing=forward,half=bottom]". Direction values may be local (forward/back/
// left/right); the Minecraft-side resolver maps them to world directions and validates
// every property against the real block.
public record BlockSpec(String id, Map<String, String> state) {
    public static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());

    public static BlockSpec parse(String text) {
        if (text == null || text.isBlank()) throw new PlanException("Missing block");
        String s = text.trim().toLowerCase(Locale.ROOT);
        int bracket = s.indexOf('[');
        String id = (bracket < 0 ? s : s.substring(0, bracket)).trim();
        Map<String, String> state = new LinkedHashMap<>();
        if (bracket >= 0) {
            if (!s.endsWith("]")) throw new PlanException("Malformed block state in '" + text + "'");
            String body = s.substring(bracket + 1, s.length() - 1).trim();
            if (!body.isEmpty()) {
                // Commas per vanilla syntax; models also write spaces ("enabled=true facing=down").
                for (String pair : body.split("[,\\s]+")) {
                    String[] kv = pair.split("=", -1);
                    if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                        throw new PlanException("Malformed block state '" + pair.trim() + "' in '" + text + "'");
                    }
                    state.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        if (id.isEmpty()) throw new PlanException("Missing block id in '" + text + "'");
        if (!id.contains(":")) id = "minecraft:" + id;
        return new BlockSpec(id, Collections.unmodifiableMap(state));
    }

    public BlockSpec with(String key, String value) {
        Map<String, String> next = new LinkedHashMap<>(state);
        next.put(key, value);
        return new BlockSpec(id, Collections.unmodifiableMap(next));
    }

    public BlockSpec withDefault(String key, String value) {
        return state.containsKey(key) ? this : with(key, value);
    }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    @Override
    public String toString() {
        if (state.isEmpty()) return id;
        return id + state.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(",", "[", "]"));
    }
}
