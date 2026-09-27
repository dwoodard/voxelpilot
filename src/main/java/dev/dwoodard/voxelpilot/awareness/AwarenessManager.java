package dev.dwoodard.voxelpilot.awareness;

import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class AwarenessManager {
    private static final AwarenessManager INSTANCE = new AwarenessManager();
    private final TerrainAwarenessProvider terrain = new TerrainAwarenessProvider();

    private AwarenessManager() {}

    public static AwarenessManager get() { return INSTANCE; }

    public Optional<AwarenessState> current(Minecraft mc) {
        if (mc.player == null) return Optional.empty();

        return WayfinderManager.get().active().map(target -> {
            Vec3 player = mc.player.position();
            Vec3 targetCenter = Vec3.atCenterOf(target.pos());
            double dx = targetCenter.x - player.x;
            double dz = targetCenter.z - player.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            int vertical = target.pos().getY() - mc.player.blockPosition().getY();

            Vec3 approachCenter = Vec3.atCenterOf(target.approach());
            double approachDx = approachCenter.x - player.x;
            double approachDz = approachCenter.z - player.z;
            double approachDistance = Math.sqrt(approachDx * approachDx + approachDz * approachDz);

            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double relativeBearing = Mth.wrapDegrees(bearing - mc.player.getYRot());

            AwarenessLevel level;
            if (horizontal <= 8 && Math.abs(vertical) <= 8) {
                level = AwarenessLevel.PRECISION;
            } else if (approachDistance <= 24) {
                level = AwarenessLevel.APPROACH;
            } else {
                level = AwarenessLevel.NAVIGATION;
            }

            return new AwarenessState(
                level,
                target.name(),
                target.pos(),
                target.approach(),
                horizontal,
                approachDistance,
                vertical,
                relativeBearing,
                terrain.observe(mc, target.pos())
            );
        });
    }

    public enum AwarenessLevel {
        NAVIGATION,
        APPROACH,
        PRECISION
    }

    public record AwarenessState(
        AwarenessLevel level,
        String targetName,
        BlockPos targetPosition,
        BlockPos approachPosition,
        double horizontalDistance,
        double approachDistance,
        int verticalDistance,
        double relativeBearing,
        java.util.List<TerrainAwarenessProvider.Observation> observations
    ) {}
}
