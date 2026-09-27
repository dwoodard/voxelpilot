package dev.dwoodard.voxelpilot.ui;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public final class CommandUsageStore {
    private static final CommandUsageStore INSTANCE = new CommandUsageStore();
    private final Properties usage = new Properties();
    private boolean loaded;

    private CommandUsageStore() {}

    public static CommandUsageStore get() { return INSTANCE; }

    public synchronized void record(String command) {
        if (command == null || !command.startsWith("/")) return;
        load();
        long now = System.currentTimeMillis();
        int count = intValue(command + ".count");
        usage.setProperty(command + ".count", Integer.toString(count + 1));
        usage.setProperty(command + ".last", Long.toString(now));
        save();
    }

    public synchronized int score(String command) {
        load();
        int count = intValue(command + ".count");
        long last = longValue(command + ".last");
        long ageHours = last == 0 ? Long.MAX_VALUE : Math.max(0, (System.currentTimeMillis() - last) / 3_600_000L);
        int recency = ageHours < 1 ? 500 : ageHours < 24 ? 250 : ageHours < 168 ? 100 : 0;
        return Math.min(count, 100) * 20 + recency;
    }

    public synchronized List<PaletteSuggestionService.Item> matching(String prefix, int limit) {
        load();
        List<String> commands = new ArrayList<>();
        for (String key : usage.stringPropertyNames()) {
            if (!key.endsWith(".count")) continue;
            String command = key.substring(0, key.length() - 6);
            if (command.startsWith(prefix)) commands.add(command);
        }
        commands.sort(Comparator.comparingInt(this::score).reversed());
        return commands.stream().limit(limit)
            .map(command -> new PaletteSuggestionService.Item(
                command, command, PaletteSuggestionService.Source.RECENT))
            .toList();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) return;
        try (InputStream in = Files.newInputStream(path)) {
            usage.load(in);
        } catch (IOException ignored) {}
    }

    private void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                usage.store(out, "Voxel Pilot command usage");
            }
        } catch (IOException ignored) {}
    }

    private Path path() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config")
            .resolve("voxelpilot-command-usage.properties");
    }

    private int intValue(String key) {
        try { return Integer.parseInt(usage.getProperty(key, "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private long longValue(String key) {
        try { return Long.parseLong(usage.getProperty(key, "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
