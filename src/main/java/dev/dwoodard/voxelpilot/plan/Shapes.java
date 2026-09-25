package dev.dwoodard.voxelpilot.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Deterministic geometry for each component type. Every shape renders relative to its own
// origin (0,0,0); PlanRenderer places it. Orientation-dependent states (stair facing, door
// halves) are computed here so the model never has to get them right.
final class Shapes {
    static final int MAX_DIM = 128;
    static final int MAX_RAW_BLOCKS = 512;
    static final String TYPES = "box, stairs, roof, door, blocks";

    private Shapes() {}

    static List<LocalChange> render(PlanNode node) {
        String type = node.type == null ? "" : node.type.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "box" -> box(node);
            case "stairs" -> stairs(node);
            case "roof" -> roof(node);
            case "door" -> door(node);
            case "blocks" -> blocks(node);
            default -> throw new PlanException("unknown type '" + node.type + "' (use " + TYPES + ")");
        };
    }

    private static List<LocalChange> box(PlanNode node) {
        int[] s = size(node);
        int w = s[0], h = s[1], d = s[2];
        BlockSpec block = required(node.block, "block");
        String fill = node.fill == null ? "solid" : node.fill.trim().toLowerCase(Locale.ROOT);
        if (!List.of("solid", "hollow", "walls", "outline").contains(fill)) {
            throw new PlanException("unknown fill '" + node.fill + "' (use solid, hollow, walls, outline)");
        }
        List<LocalChange> out = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    boolean ex = x == 0 || x == w - 1, ey = y == 0 || y == h - 1, ez = z == 0 || z == d - 1;
                    int edges = (ex ? 1 : 0) + (ey ? 1 : 0) + (ez ? 1 : 0);
                    BlockSpec b = switch (fill) {
                        case "hollow" -> edges > 0 ? block : BlockSpec.AIR;
                        case "walls" -> ex || ez ? block : null;
                        case "outline" -> edges >= 2 ? block : null;
                        default -> block;
                    };
                    if (b != null) out.add(new LocalChange(x, y, z, b));
                }
            }
        }
        return out;
    }

    private static List<LocalChange> stairs(PlanNode node) {
        Dir dir = Dir.parse(node.direction, Dir.FORWARD);
        int length = range(node.length, "length", 1, MAX_DIM);
        int width = node.width == null ? 1 : range(node.width, "width", 1, 16);
        String vertical = node.vertical == null ? "up" : node.vertical.trim().toLowerCase(Locale.ROOT);
        if (!vertical.equals("up") && !vertical.equals("down")) throw new PlanException("vertical must be up or down");
        boolean up = vertical.equals("up");
        boolean carve = node.carve != null ? node.carve : !up;

        // Stair "facing" is the direction you walk to go up it.
        BlockSpec step = required(node.block, "block").with("facing", (up ? dir : dir.opposite()).key()).withDefault("half", "bottom");
        Dir side = dir.clockwise();
        List<LocalChange> out = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int y = up ? i : -i;
            for (int wi = 0; wi < width; wi++) {
                int x = dir.dx * i + side.dx * wi;
                int z = dir.dz * i + side.dz * wi;
                if (carve) for (int h = 1; h <= 3; h++) out.add(new LocalChange(x, y + h, z, BlockSpec.AIR));
                out.add(new LocalChange(x, y, z, step));
            }
        }
        return out;
    }

    // Gable roof over a footprint of size[0] (right) x size[2] (forward); size[1] is ignored
    // because the pitch fixes the height. "direction" picks the ridge axis: forward/back runs
    // the ridge away from the player, left/right runs it across.
    private static List<LocalChange> roof(PlanNode node) {
        int[] s = size(node);
        Dir dir = Dir.parse(node.direction, Dir.FORWARD);
        boolean ridgeForward = dir == Dir.FORWARD || dir == Dir.BACK;
        int span = ridgeForward ? s[0] : s[2];
        int len = ridgeForward ? s[2] : s[0];
        Dir plus = ridgeForward ? Dir.RIGHT : Dir.FORWARD;

        BlockSpec stair = required(node.block, "block");
        BlockSpec rising = stair.with("facing", plus.key()).withDefault("half", "bottom");
        BlockSpec falling = stair.with("facing", plus.opposite().key()).withDefault("half", "bottom");
        BlockSpec ridge = node.ridge != null ? BlockSpec.parse(node.ridge) : defaultRidge(stair);
        BlockSpec ends = node.ends != null ? BlockSpec.parse(node.ends) : null;

        List<LocalChange> out = new ArrayList<>();
        for (int i = 0; i <= span - 1 - i; i++) {
            int a1 = i, a2 = span - 1 - i;
            for (int b = 0; b < len; b++) {
                if (a1 == a2) {
                    out.add(roofBlock(ridgeForward, a1, i, b, ridge));
                } else {
                    out.add(roofBlock(ridgeForward, a1, i, b, rising));
                    out.add(roofBlock(ridgeForward, a2, i, b, falling));
                }
            }
            if (ends != null) {
                for (int a = a1 + 1; a < a2; a++) {
                    out.add(roofBlock(ridgeForward, a, i, 0, ends));
                    if (len > 1) out.add(roofBlock(ridgeForward, a, i, len - 1, ends));
                }
            }
        }
        return out;
    }

    private static LocalChange roofBlock(boolean ridgeForward, int across, int y, int along, BlockSpec block) {
        return ridgeForward ? new LocalChange(across, y, along, block) : new LocalChange(along, y, across, block);
    }

    // oak_stairs -> oak_slab is right for every vanilla stair family; if a modded one has no
    // slab, the resolver rejects it and the model is told to set "ridge" explicitly.
    private static BlockSpec defaultRidge(BlockSpec stair) {
        if (stair.id().endsWith("_stairs")) {
            String slab = stair.id().substring(0, stair.id().length() - "_stairs".length()) + "_slab";
            return BlockSpec.parse(slab).with("type", "bottom");
        }
        return new BlockSpec(stair.id(), java.util.Map.of());
    }

    private static List<LocalChange> door(PlanNode node) {
        Dir dir = Dir.parse(node.direction, Dir.FORWARD);
        BlockSpec door = node.block == null ? BlockSpec.parse("minecraft:oak_door") : BlockSpec.parse(node.block);
        door = door.with("facing", dir.key()).withDefault("hinge", "left");
        return List.of(
            new LocalChange(0, 0, 0, door.with("half", "lower")),
            new LocalChange(0, 1, 0, door.with("half", "upper")));
    }

    private static List<LocalChange> blocks(PlanNode node) {
        if (node.blocks == null || node.blocks.isEmpty()) throw new PlanException("blocks list is empty");
        if (node.blocks.size() > MAX_RAW_BLOCKS) {
            throw new PlanException("more than " + MAX_RAW_BLOCKS + " blocks; use box/stairs/roof for large shapes");
        }
        List<LocalChange> out = new ArrayList<>();
        for (PlanNode.RawBlock raw : node.blocks) {
            int[] p = triple(raw.at, "blocks[].at");
            out.add(new LocalChange(p[0], p[1], p[2], required(raw.block, "blocks[].block")));
        }
        return out;
    }

    private static int[] size(PlanNode node) {
        int[] s = triple(node.size, "size");
        for (int v : s) if (v < 1 || v > MAX_DIM) throw new PlanException("size values must be 1.." + MAX_DIM);
        return s;
    }

    static int[] triple(int[] values, String name) {
        if (values == null || values.length != 3) throw new PlanException(name + " must be [x, y, z]");
        return values;
    }

    private static int range(Integer value, String name, int min, int max) {
        if (value == null) throw new PlanException(name + " is required");
        if (value < min || value > max) throw new PlanException(name + " must be " + min + ".." + max);
        return value;
    }

    private static BlockSpec required(String block, String name) {
        if (block == null || block.isBlank()) throw new PlanException(name + " is required");
        return BlockSpec.parse(block);
    }
}
