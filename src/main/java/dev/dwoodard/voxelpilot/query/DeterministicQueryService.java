package dev.dwoodard.voxelpilot.query;

import dev.dwoodard.voxelpilot.reference.ReferenceResolver;
import net.minecraft.client.Minecraft;
import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;

import java.util.stream.Collectors;

/** Read-only deterministic queries. This service never invokes AI or mutates world state. */
public final class DeterministicQueryService {
    private DeterministicQueryService() {}

    public static String query(Minecraft mc, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("@")) {
            return ReferenceResolver.resolve(mc, value.substring(1))
                .map(ReferenceResolver.ResolvedReference::promptContext)
                .orElse(value + " is UNKNOWN");
        }
        if (value.equalsIgnoreCase("players")) {
            if (mc == null || mc.getConnection() == null) return "Players: UNKNOWN";
            String names = mc.getConnection().getOnlinePlayers().stream()
                .map(info -> info.getProfile().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(", "));
            return names.isBlank() ? "Players: none detected" : "Players: " + names;
        }
        if (!value.isBlank() && !value.toLowerCase().contains(" near ")) {
            var target = WayfinderManager.get().inspectNearest(mc, value);
            if (target.isPresent() && mc != null && mc.player != null) {
                return WayfinderManager.get().describe(target.get(), mc.player.blockPosition());
            }
            return WayfinderManager.get().searchFailure(value);
        }
        return "Unsupported deterministic query shape. Try ?@reference, ?players, or ?diamond_ore";
    }
}
