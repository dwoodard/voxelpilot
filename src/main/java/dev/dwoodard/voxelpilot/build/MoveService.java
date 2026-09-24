package dev.dwoodard.voxelpilot.build;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class MoveService {
    private MoveService() {}

    public static BuildExecutor.Result move(Minecraft mc, double x, double y, double z) {
        if (mc.player == null) return BuildExecutor.Result.fail("Player unavailable");
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return BuildExecutor.Result.fail("Player movement is only available in local single-player worlds");
        ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (player == null) return BuildExecutor.Result.fail("Player unavailable");
        server.execute(() -> player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot()));
        return BuildExecutor.Result.ok("Moving to " + (int)x + " " + (int)y + " " + (int)z);
    }
}
