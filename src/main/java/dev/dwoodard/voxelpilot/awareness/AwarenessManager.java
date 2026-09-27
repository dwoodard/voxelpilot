package dev.dwoodard.voxelpilot.awareness;

import dev.dwoodard.voxelpilot.wayfinder.WayfinderManager;
import dev.dwoodard.voxelpilot.reference.ObservationState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;

public final class AwarenessManager {
    private static final AwarenessManager INSTANCE = new AwarenessManager();
    private final TerrainAwarenessProvider terrain = new TerrainAwarenessProvider();

    private AwarenessState snapshot;
    private BlockPos trackedTarget;
    private Phase phase = Phase.NAVIGATING;
    private int scanCooldown;

    private AwarenessManager() {}

    public static AwarenessManager get() { return INSTANCE; }

    public Optional<AwarenessState> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }

        var active = WayfinderManager.get().active();
        if (active.isEmpty()) {
            clear();
            return;
        }

        var target = active.get();
        if (!target.pos().equals(trackedTarget)) {
            trackedTarget = target.pos();
            phase = Phase.NAVIGATING;
            scanCooldown = 0;
        }

        Vec3 player = mc.player.position();
        double horizontal = horizontalDistance(player, target.pos());
        int vertical = target.pos().getY() - mc.player.blockPosition().getY();
        double approachDistance = horizontalDistance(player, target.approach());

        double targetDistance = Math.sqrt(mc.player.blockPosition().distSqr(target.pos()));
        ObservationState targetObservation = WayfinderManager.get().observationState(mc, target);
        if (targetObservation == ObservationState.NO_LONGER_PRESENT) phase = Phase.TARGET_LOST;
        else if (targetDistance <= 4) phase = Phase.REACHED;
        else if (approachDistance <= 3) phase = Phase.TARGETING;
        else if (approachDistance <= 24) phase = Phase.APPROACHING_ENTRY;
        else phase = Phase.NAVIGATING;

        Vec3 targetCenter = Vec3.atCenterOf(target.pos());
        double bearing = Math.toDegrees(Math.atan2(-(targetCenter.x - player.x), targetCenter.z - player.z));
        double relativeBearing = Mth.wrapDegrees(bearing - mc.player.getYRot());

        List<TerrainAwarenessProvider.Observation> observations =
            snapshot == null ? List.of() : snapshot.observations();
        if (scanCooldown-- <= 0) {
            observations = terrain.observe(mc, target.pos(), target.approach());
            scanCooldown = 10;
        }

        snapshot = new AwarenessState(
            phase,
            target.name(),
            target.pos(),
            target.approach(),
            horizontal,
            approachDistance,
            vertical,
            relativeBearing,
            targetObservation,
            observations
        );
    }

    private static double horizontalDistance(Vec3 player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = center.x - player.x;
        double dz = center.z - player.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void clear() {
        snapshot = null;
        trackedTarget = null;
        phase = Phase.NAVIGATING;
        scanCooldown = 0;
    }

    public enum Phase {
        NAVIGATING,
        APPROACHING_ENTRY,
        TARGETING,
        REACHED,
        TARGET_LOST
    }

    public record AwarenessState(
        Phase phase,
        String targetName,
        BlockPos targetPosition,
        BlockPos approachPosition,
        double horizontalDistance,
        double approachDistance,
        int verticalDistance,
        double relativeBearing,
        ObservationState targetObservation,
        List<TerrainAwarenessProvider.Observation> observations
    ) {}
}
