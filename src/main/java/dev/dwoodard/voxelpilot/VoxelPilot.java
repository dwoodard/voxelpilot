package dev.dwoodard.voxelpilot;

import com.mojang.logging.LogUtils;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.client.ClientEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(VoxelPilot.MOD_ID)
public final class VoxelPilot {
    public static final String MOD_ID = "voxelpilot";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VoxelPilot() {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        MinecraftForge.EVENT_BUS.register(BuildExecutor.get());
        LOGGER.info("VoxelPilot loaded");
    }
}
