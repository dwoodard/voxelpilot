package dev.dwoodard.voxelpilot.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.Frame;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.build.ResolvedChange;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// What an agent can perceive, all read-only. Pictures for understanding (a screenshot and a
// labeled top-down map), exact text for planning (state, region inspection). Everything is
// expressed in the plan's local frame (x = right, z = forward), the same coordinates a
// script uses, so what the agent reads off the map it can write straight into a plan.
final class Perception {
    private static final int MAX_INSPECT = 4096;
    private static final String CODES = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private Perception() {}

    // The frame an agent should plan in: the current preview's, else the selection's, else
    // one centered on the player facing where they face.
    static Frame frame(Minecraft mc) {
        return GhostPreviewManager.get().frame().or(() -> Frame.current(mc))
            .orElseGet(() -> new Frame(mc.player.blockPosition().below(), mc.player.getDirection(), 0, 0, 0));
    }

    // Client thread.
    static JsonObject state(Minecraft mc) {
        JsonObject root = new JsonObject();
        if (mc.player == null || mc.level == null) {
            root.addProperty("inWorld", false);
            return root;
        }
        root.addProperty("inWorld", true);
        root.addProperty("singleplayer", mc.getSingleplayerServer() != null);
        root.addProperty("gameMode", mc.player.getAbilities().instabuild ? "creative" : "survival");

        Frame frame = frame(mc);
        JsonObject f = new JsonObject();
        f.add("originWorld", xyz(frame.origin()));
        f.addProperty("forward", frame.forward().getSerializedName());
        f.addProperty("axes", "x = right, y = up, z = forward");
        root.add("frame", f);

        int[] p = frame.toLocal(mc.player.blockPosition());
        JsonObject player = new JsonObject();
        player.add("local", ints(p[0], p[1], p[2]));
        player.add("world", xyz(mc.player.blockPosition()));
        player.addProperty("facing", mc.player.getDirection().getSerializedName());
        root.add("player", player);

        SelectionManager.get().box().ifPresent(box -> {
            JsonObject s = new JsonObject();
            s.add("size", ints(frame.width(), frame.height(), frame.depth()));
            s.add("worldMin", xyz(box.min()));
            s.add("worldMax", xyz(box.max()));
            root.add("selection", s);
        });

        SelectionManager.get().crosshairTarget(mc).ifPresent(pos -> {
            JsonObject look = new JsonObject();
            int[] c = frame.toLocal(pos);
            look.add("local", ints(c[0], c[1], c[2]));
            look.addProperty("block", id(mc.level.getBlockState(pos)));
            root.add("lookingAt", look);
        });

        GhostPreviewManager.get().plan().ifPresent(plan -> {
            JsonObject preview = new JsonObject();
            preview.addProperty("revision", GhostPreviewManager.get().revision());
            preview.addProperty("drafting", GhostPreviewManager.get().drafting());
            preview.addProperty("title", plan.plan().title);
            preview.addProperty("changes", plan.changes().size());
            preview.addProperty("replacesExisting", plan.replacedExisting());
            preview.addProperty("outsideSelection", plan.outsideSelection());
            JsonArray script = new JsonArray();
            plan.plan().script.forEach(script::add);
            preview.add("script", script);
            JsonArray notes = new JsonArray();
            plan.notes().forEach(notes::add);
            preview.add("notes", notes);
            root.add("preview", preview);
        });

        BuildExecutor be = BuildExecutor.get();
        JsonObject build = new JsonObject();
        build.addProperty("active", be.active());
        build.addProperty("paused", be.paused());
        build.addProperty("completed", be.completed());
        build.addProperty("total", be.total());
        build.addProperty("speed", be.speed().name().toLowerCase());
        root.add("build", build);
        return root;
    }

    // Render thread: exactly what the player sees, ghost and HUD included.
    static byte[] screenshot(Minecraft mc) throws IOException {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            return image.asByteArray();
        }
    }

    record MapData(Frame frame, int x0, int z0, int size, int[][] color, int[][] height, int[][] overlay,
                   int[] playerLocal, int[] selection) {}

    // Client thread: sample top surface colors/heights plus overlays, in local coordinates.
    // Centered on the selection (or the frame origin), radius blocks each way.
    static MapData sampleMap(Minecraft mc, int radius) {
        Frame frame = frame(mc);
        int cx = frame.hasSelection() ? frame.width() / 2 : 0;
        int cz = frame.hasSelection() ? frame.depth() / 2 : 0;
        int r = frame.hasSelection() ? Math.max(radius, Math.max(frame.width(), frame.depth()) / 2 + 6) : radius;
        r = Math.min(r, 96);
        int size = r * 2 + 1, x0 = cx - r, z0 = cz - r;

        int[][] color = new int[size][size], height = new int[size][size], overlay = new int[size][size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                BlockPos column = frame.toWorld(x0 + i, 0, z0 + j);
                int top = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ()) - 1;
                BlockPos pos = new BlockPos(column.getX(), top, column.getZ());
                BlockState state = mc.level.getBlockState(pos);
                color[j][i] = state.isAir() ? 0x000000 : state.getMapColor(mc.level, pos).col;
                height[j][i] = top - frame.origin().getY();
            }
        }
        // Ghost: 1 = additions in this column, 2 = removals, 3 = both.
        Map<Long, Integer> ghost = new HashMap<>();
        for (ResolvedChange change : GhostPreviewManager.get().changes()) {
            int[] l = frame.toLocal(change.pos());
            ghost.merge(((long) l[0] << 32) | (l[2] & 0xffffffffL), change.removal() ? 2 : 1, (a, b) -> a | b);
        }
        ghost.forEach((key, kind) -> {
            int i = (int) (key >> 32) - x0, j = key.intValue() - z0;
            if (i >= 0 && i < size && j >= 0 && j < size) overlay[j][i] = kind;
        });
        int[] p = frame.toLocal(mc.player.blockPosition());
        int[] selection = frame.hasSelection() ? new int[]{0, 0, frame.width() - 1, frame.depth() - 1} : null;
        return new MapData(frame, x0, z0, size, color, height, overlay, p, selection);
    }

    // Any thread: draw the map. Forward is up; x grows to the right; grid every 8 blocks with
    // local coordinate labels so positions can be read straight off the image.
    static byte[] drawMap(MapData m) throws IOException {
        int scale = Math.max(4, Math.min(12, 640 / m.size()));
        int margin = 28;
        int px = m.size() * scale;
        BufferedImage image = new BufferedImage(px + margin * 2, px + margin * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x16181B));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        for (int j = 0; j < m.size(); j++) {
            for (int i = 0; i < m.size(); i++) {
                int rgb = m.color()[j][i];
                // Shade like vanilla maps: brighter where higher than the block behind it.
                int behind = j > 0 ? m.height()[j - 1][i] : m.height()[j][i];
                double shade = m.height()[j][i] > behind ? 1.0 : m.height()[j][i] < behind ? 0.72 : 0.86;
                Color c = new Color(clamp(((rgb >> 16) & 255) * shade), clamp(((rgb >> 8) & 255) * shade), clamp((rgb & 255) * shade));
                int kind = m.overlay()[j][i];
                if (kind != 0) c = blend(c, kind == 2 ? new Color(0xFF4040) : new Color(0x40D0FF), 0.55);
                g.setColor(c);
                g.fillRect(margin + i * scale, margin + (m.size() - 1 - j) * scale, scale, scale);
            }
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.setStroke(new BasicStroke(1));
        for (int local = ceilTo(m.x0(), 8); local < m.x0() + m.size(); local += 8) {
            int x = margin + (local - m.x0()) * scale;
            g.setColor(new Color(255, 255, 255, 40));
            g.drawLine(x, margin, x, margin + px);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("x" + local, x - 6, margin - 6);
        }
        for (int local = ceilTo(m.z0(), 8); local < m.z0() + m.size(); local += 8) {
            int y = margin + (m.size() - 1 - (local - m.z0())) * scale + scale;
            g.setColor(new Color(255, 255, 255, 40));
            g.drawLine(margin, y, margin + px, y);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("z" + local, 2, y + 4);
        }

        if (m.selection() != null) {
            int[] s = m.selection();
            g.setColor(new Color(0xF2C026));
            g.setStroke(new BasicStroke(2));
            int left = margin + (s[0] - m.x0()) * scale, right = margin + (s[2] - m.x0() + 1) * scale;
            int top = margin + (m.size() - 1 - (s[3] - m.z0())) * scale, bottom = margin + (m.size() - (s[1] - m.z0())) * scale;
            g.drawRect(left, top, right - left, bottom - top);
        }

        int pi = m.playerLocal()[0] - m.x0(), pj = m.playerLocal()[2] - m.z0();
        if (pi >= 0 && pi < m.size() && pj >= 0 && pj < m.size()) {
            g.setColor(Color.WHITE);
            int cx = margin + pi * scale + scale / 2, cy = margin + (m.size() - 1 - pj) * scale + scale / 2;
            g.fillOval(cx - 4, cy - 4, 8, 8);
            g.setColor(Color.BLACK);
            g.drawOval(cx - 4, cy - 4, 8, 8);
        }

        g.setColor(Color.LIGHT_GRAY);
        g.drawString("forward ↑   yellow = selection   blue = ghost adds   red = ghost removes   white = player",
            margin, image.getHeight() - 8);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // Client thread: exact blocks in a local box, one character per block with a legend.
    // Layers top to bottom; rows far (high z) to near, matching the map's orientation.
    static String inspect(Minecraft mc, int x, int y, int z, int w, int h, int d) {
        if (w < 1 || h < 1 || d < 1) return "error: size must be positive";
        if ((long) w * h * d > MAX_INSPECT) return "error: region too large (max " + MAX_INSPECT + " blocks); inspect a smaller box";
        Frame frame = frame(mc);
        Map<String, Character> legend = new LinkedHashMap<>();
        StringBuilder grid = new StringBuilder();
        for (int ly = y + h - 1; ly >= y; ly--) {
            grid.append("y=").append(ly).append('\n');
            for (int lz = z + d - 1; lz >= z; lz--) {
                grid.append(String.format("z=%-4d ", lz));
                for (int lx = x; lx < x + w; lx++) {
                    BlockState state = mc.level.getBlockState(frame.toWorld(lx, ly, lz));
                    if (state.isAir()) { grid.append('.'); continue; }
                    String name = describe(state);
                    Character code = legend.get(name);
                    if (code == null) {
                        code = legend.size() < CODES.length() ? CODES.charAt(legend.size()) : '?';
                        legend.put(name, code);
                    }
                    grid.append(code);
                }
                grid.append('\n');
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("region x ").append(x).append("..").append(x + w - 1).append(", y ").append(y).append("..").append(y + h - 1)
            .append(", z ").append(z).append("..").append(z + d - 1).append(" (columns left to right = x ascending)\n");
        out.append("legend: . = air");
        legend.forEach((name, code) -> out.append(", ").append(code).append(" = ").append(name));
        out.append("\n\n").append(grid);
        return out.toString();
    }

    private static String describe(BlockState state) {
        String name = id(state);
        if (state.getValues().isEmpty()) return name;
        StringBuilder s = new StringBuilder(name).append('[');
        boolean first = true;
        for (var entry : state.getValues().entrySet()) {
            if (!first) s.append(',');
            first = false;
            s.append(entry.getKey().getName()).append('=').append(entry.getValue());
        }
        return s.append(']').toString();
    }

    private static String id(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }

    private static JsonArray xyz(BlockPos pos) { return ints(pos.getX(), pos.getY(), pos.getZ()); }

    private static JsonArray ints(int... values) {
        JsonArray array = new JsonArray();
        for (int v : values) array.add(v);
        return array;
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

    private static Color blend(Color a, Color b, double t) {
        return new Color(clamp(a.getRed() * (1 - t) + b.getRed() * t), clamp(a.getGreen() * (1 - t) + b.getGreen() * t), clamp(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static int ceilTo(int value, int step) { return Math.floorDiv(value + step - 1, step) * step; }
}
