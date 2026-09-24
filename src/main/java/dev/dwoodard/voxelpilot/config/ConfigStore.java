package dev.dwoodard.voxelpilot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dwoodard.voxelpilot.VoxelPilot;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("voxelpilot.json");
    private static ProviderConfig current;

    private ConfigStore() {}

    public static synchronized ProviderConfig get() {
        if (current == null) current = load();
        return current;
    }

    public static synchronized void save(ProviderConfig config) {
        current = config;
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(config));
        } catch (IOException e) {
            VoxelPilot.LOGGER.error("Failed to save VoxelPilot config", e);
        }
    }

    private static ProviderConfig load() {
        if (!Files.exists(PATH)) {
            ProviderConfig config = new ProviderConfig();
            save(config);
            return config;
        }
        try {
            ProviderConfig parsed = GSON.fromJson(Files.readString(PATH), ProviderConfig.class);
            return parsed == null ? new ProviderConfig() : parsed;
        } catch (Exception e) {
            VoxelPilot.LOGGER.error("Failed to read VoxelPilot config", e);
            return new ProviderConfig();
        }
    }
}
