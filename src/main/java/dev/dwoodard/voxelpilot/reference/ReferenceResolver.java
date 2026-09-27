package dev.dwoodard.voxelpilot.reference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves @ tokens into factual Minecraft context.
 *
 * This layer never invokes AI and never invents unavailable facts. Consumers such as
 * Cmd-K, HUD, Wayfinder, commands, and AI all receive the same reference representation.
 */
public final class ReferenceResolver {
    private static final Pattern TOKEN = Pattern.compile("@([A-Za-z0-9_]{1,32})");

    private ReferenceResolver() {}

    public static List<ResolvedReference> resolveAll(Minecraft mc, String input) {
        if (input == null || input.isBlank()) return List.of();

        List<ResolvedReference> resolved = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(input);
        while (matcher.find()) {
            resolve(mc, matcher.group(1)).ifPresent(ref -> {
                if (resolved.stream().noneMatch(existing -> existing.token().equalsIgnoreCase(ref.token()))) {
                    resolved.add(ref);
                }
            });
        }
        return List.copyOf(resolved);
    }

    public static Optional<ResolvedReference> resolve(Minecraft mc, String name) {
        if (mc == null || mc.getConnection() == null || name == null || name.isBlank()) return Optional.empty();

        PlayerInfo info = mc.getConnection().getOnlinePlayers().stream()
            .filter(candidate -> candidate.getProfile().getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
        if (info == null) return Optional.empty();

        String canonicalName = info.getProfile().getName();
        Player entity = mc.level == null ? null : mc.level.getPlayerByUUID(info.getProfile().getId());

        if (entity == null) {
            return Optional.of(new ResolvedReference(
                "@" + canonicalName, "PLAYER", canonicalName, true,
                null, null, null, null, null, "DIRECT"
            ));
        }

        String dimension = entity.level().dimension().location().toString();
        var pos = entity.blockPosition();
        Double distance = null;
        if (mc.player != null && mc.player.level().dimension().equals(entity.level().dimension())) {
            distance = mc.player.distanceTo(entity);
        }

        return Optional.of(new ResolvedReference(
            "@" + canonicalName, "PLAYER", canonicalName, true,
            dimension, pos.getX(), pos.getY(), pos.getZ(), distance, "DIRECT"
        ));
    }

    public static List<ResolvedReference> matchingPlayers(Minecraft mc, String needle, int limit) {
        if (mc == null || mc.getConnection() == null) return List.of();
        String query = needle == null ? "" : needle.toLowerCase(Locale.ROOT);

        return mc.getConnection().getOnlinePlayers().stream()
            .map(PlayerInfo::getProfile)
            .filter(profile -> query.isBlank() || profile.getName().toLowerCase(Locale.ROOT).startsWith(query))
            .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()))
            .limit(limit)
            .map(profile -> resolve(mc, profile.getName()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    public record ResolvedReference(
        String token,
        String type,
        String name,
        boolean online,
        String dimension,
        Integer x,
        Integer y,
        Integer z,
        Double distance,
        String evidence
    ) {
        public boolean hasPosition() {
            return dimension != null && x != null && y != null && z != null;
        }

        public String paletteLabel() {
            StringBuilder out = new StringBuilder(token).append("  ·  ").append(type);
            if (online) out.append(" · online");
            if (distance != null) out.append(" · ").append(Math.round(distance)).append("m");
            else if (!hasPosition()) out.append(" · position unknown");
            return out.toString();
        }

        public String promptContext() {
            StringBuilder out = new StringBuilder();
            out.append(token).append("\n")
                .append("  type: ").append(type).append("\n")
                .append("  name: ").append(name).append("\n")
                .append("  online: ").append(online).append("\n");
            if (hasPosition()) {
                out.append("  dimension: ").append(dimension).append("\n")
                    .append("  position: [").append(x).append(", ").append(y).append(", ").append(z).append("]\n");
                if (distance != null) out.append("  distance_from_player: ").append(Math.round(distance)).append("\n");
            } else {
                out.append("  position: UNKNOWN\n");
            }
            out.append("  evidence: ").append(evidence);
            return out.toString();
        }
    }
}
