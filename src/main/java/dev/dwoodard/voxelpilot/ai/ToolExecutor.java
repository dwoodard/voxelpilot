package dev.dwoodard.voxelpilot.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dwoodard.voxelpilot.reference.ReferenceStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public interface ToolExecutor {
    String execute(String functionName, JsonObject arguments);

    static ToolExecutor create(Minecraft mc) {
        return new DefaultToolExecutor(mc);
    }

    // Tool calls run off the client thread (the model's HTTP stream completes on
    // ModelProvider.STREAM_EXECUTOR), so every world read hops onto the main thread via
    // mc.submit, same as the rest of AiPlanner. Regions are capped so a careless model
    // request can't stall the game scanning a huge volume.
    class DefaultToolExecutor implements ToolExecutor {
        private static final Gson GSON = new Gson();
        private static final int MAX_REGION_VOLUME = 32 * 32 * 32;
        private static final int MAX_SEARCH_RADIUS = 64;
        private static final int MAX_TERRAIN_SPAN = 64;
        private static final int MAX_TERRAIN_SAMPLES = 400;

        private final Minecraft mc;

        DefaultToolExecutor(Minecraft mc) { this.mc = mc; }

        @Override
        public String execute(String functionName, JsonObject arguments) {
            try {
                return switch (functionName) {
                    case "query_blocks" -> queryBlocks(arguments);
                    case "find_nearest_block" -> findNearestBlock(arguments);
                    case "analyze_terrain" -> analyzeTerrain(arguments);
                    case "get_player_state" -> getPlayerState(arguments);
                    case "check_inventory_available" -> checkInventoryAvailable(arguments);
                    case "measure_distance" -> measureDistance(arguments);
                    case "check_line_of_sight" -> checkLineOfSight(arguments);
                    case "list_references" -> listReferences(arguments);
                    case "check_constraints" -> checkConstraints(arguments);
                    default -> error("Unknown tool: " + functionName);
                };
            } catch (Exception e) {
                return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        private String queryBlocks(JsonObject args) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > MAX_REGION_VOLUME) {
                return error("Region too large (" + volume + " blocks, max " + MAX_REGION_VOLUME + "); narrow the range");
            }

            return mc.submit(() -> {
                if (mc.level == null) return error("No world loaded");
                Map<String, Integer> counts = new LinkedHashMap<>();
                int total = 0, empty = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
                            total++;
                            if (state.isAir()) { empty++; continue; }
                            counts.merge(blockId(state.getBlock()), 1, Integer::sum);
                        }
                    }
                }
                JsonObject result = new JsonObject();
                JsonArray blocks = new JsonArray();
                counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("block", e.getKey());
                        entry.addProperty("count", e.getValue());
                        blocks.add(entry);
                    });
                result.add("blocks", blocks);
                result.addProperty("total", total);
                result.addProperty("empty_count", empty);
                return GSON.toJson(result);
            }).join();
        }

        private String findNearestBlock(JsonObject args) {
            String blockType = args.get("block_type").getAsString();
            int maxDistance = Math.min(args.get("max_distance").getAsInt(), MAX_SEARCH_RADIUS);
            if (maxDistance <= 0) return error("max_distance must be positive");

            return mc.submit(() -> {
                if (mc.player == null || mc.level == null) return error("No player or world loaded");
                Block wanted = matchBlock(blockType);
                if (wanted == null) return "{\"found\": false, \"reason\": \"no block matches '" + blockType + "'\"}";

                BlockPos origin = mc.player.blockPosition();
                BlockPos best = null;
                double bestDistSq = Double.MAX_VALUE;
                for (int x = -maxDistance; x <= maxDistance; x++) {
                    for (int y = -maxDistance; y <= maxDistance; y++) {
                        for (int z = -maxDistance; z <= maxDistance; z++) {
                            BlockPos pos = origin.offset(x, y, z);
                            if (!mc.level.getBlockState(pos).is(wanted)) continue;
                            double distSq = origin.distSqr(pos);
                            if (distSq < bestDistSq) { bestDistSq = distSq; best = pos; }
                        }
                    }
                }

                if (best == null) return "{\"found\": false}";
                JsonObject result = new JsonObject();
                result.addProperty("found", true);
                result.add("position", ints(best.getX(), best.getY(), best.getZ()));
                result.addProperty("distance", Math.round(Math.sqrt(bestDistSq) * 10) / 10.0);
                return GSON.toJson(result);
            }).join();
        }

        private String analyzeTerrain(JsonObject args) {
            int x1 = args.get("x1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), z2 = args.get("z2").getAsInt();
            int resolution = Math.max(1, args.has("resolution") ? args.get("resolution").getAsInt() : 1);
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            if (maxX - minX > MAX_TERRAIN_SPAN || maxZ - minZ > MAX_TERRAIN_SPAN) {
                return error("Region too large (max " + MAX_TERRAIN_SPAN + " blocks per side); narrow the range");
            }
            long samples = ((long) (maxX - minX) / resolution + 1) * ((long) (maxZ - minZ) / resolution + 1);
            if (samples > MAX_TERRAIN_SAMPLES) {
                return error("Too many sample points at this resolution; increase resolution or shrink the range");
            }

            return mc.submit(() -> {
                if (mc.level == null) return error("No world loaded");
                JsonArray surface = new JsonArray();
                int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;
                boolean anyWater = false;
                for (int x = minX; x <= maxX; x += resolution) {
                    for (int z = minZ; z <= maxZ; z += resolution) {
                        int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                        BlockState surfaceState = mc.level.getBlockState(new BlockPos(x, top, z));
                        boolean water = !surfaceState.getFluidState().isEmpty();
                        anyWater |= water;
                        minHeight = Math.min(minHeight, top);
                        maxHeight = Math.max(maxHeight, top);
                        JsonObject point = new JsonObject();
                        point.add("at", ints(x, top, z));
                        point.addProperty("block", blockId(surfaceState.getBlock()));
                        if (water) point.addProperty("water", true);
                        surface.add(point);
                    }
                }
                JsonObject result = new JsonObject();
                result.add("surface", surface);
                result.addProperty("min_height", minHeight);
                result.addProperty("max_height", maxHeight);
                result.addProperty("elevation_change", maxHeight - minHeight);
                result.addProperty("has_water", anyWater);
                return GSON.toJson(result);
            }).join();
        }

        private String getPlayerState(JsonObject args) {
            return mc.submit(() -> {
                if (mc.player == null) return error("No player loaded");
                BlockPos pos = mc.player.blockPosition();
                JsonObject result = new JsonObject();
                result.add("position", ints(pos.getX(), pos.getY(), pos.getZ()));
                result.addProperty("gamemode", mc.player.getAbilities().instabuild ? "creative" : "survival");
                result.addProperty("facing", facing(mc.player.getYRot()));

                JsonArray inventory = new JsonArray();
                for (ItemStack stack : mc.player.getInventory().items) {
                    if (stack.isEmpty()) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("item", itemId(stack));
                    entry.addProperty("count", stack.getCount());
                    inventory.add(entry);
                }
                result.add("inventory", inventory);
                return GSON.toJson(result);
            }).join();
        }

        private String checkInventoryAvailable(JsonObject args) {
            String itemType = args.get("item_type").getAsString();
            int count = args.get("count").getAsInt();
            return mc.submit(() -> {
                if (mc.player == null) return error("No player loaded");
                if (mc.player.getAbilities().instabuild) {
                    // Creative has unlimited blocks; there's nothing meaningful to "have".
                    JsonObject result = new JsonObject();
                    result.addProperty("available", true);
                    result.addProperty("current", count);
                    result.addProperty("creative", true);
                    return GSON.toJson(result);
                }
                String needle = normalize(itemType);
                int current = 0;
                for (ItemStack stack : mc.player.getInventory().items) {
                    if (stack.isEmpty()) continue;
                    if (normalize(itemId(stack)).contains(needle)) current += stack.getCount();
                }
                JsonObject result = new JsonObject();
                result.addProperty("available", current >= count);
                result.addProperty("current", current);
                return GSON.toJson(result);
            }).join();
        }

        private String measureDistance(JsonObject args) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return String.format(
                "{\"total_distance\": %.1f, \"horizontal_distance\": %.1f, \"vertical_distance\": %.1f}",
                dist, Math.sqrt(dx * dx + dz * dz), Math.abs(dy)
            );
        }

        private String checkLineOfSight(JsonObject args) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            return mc.submit(() -> {
                if (mc.level == null) return error("No world loaded");
                Vec3 from = new Vec3(x1 + 0.5, y1 + 0.5, z1 + 0.5);
                Vec3 to = new Vec3(x2 + 0.5, y2 + 0.5, z2 + 0.5);
                ClipContext context = new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
                HitResult hit = mc.level.clip(context);
                JsonObject result = new JsonObject();
                boolean clear = hit.getType() == HitResult.Type.MISS;
                result.addProperty("clear", clear);
                if (!clear && hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                    BlockPos blocker = blockHit.getBlockPos();
                    result.add("blocked_at", ints(blocker.getX(), blocker.getY(), blocker.getZ()));
                    result.addProperty("blocked_by", blockId(mc.level.getBlockState(blocker).getBlock()));
                }
                return GSON.toJson(result);
            }).join();
        }

        private String listReferences(JsonObject args) {
            String type = args.has("type") ? args.get("type").getAsString() : "";
            int maxDistance = args.has("max_distance") ? args.get("max_distance").getAsInt() : Integer.MAX_VALUE;
            return mc.submit(() -> {
                BlockPos origin = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
                JsonArray refs = new JsonArray();
                for (ReferenceStore.Place place : ReferenceStore.get().matching(type, 50)) {
                    double distance = Math.sqrt(origin.distSqr(place.position()));
                    if (distance > maxDistance) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", place.name());
                    entry.add("position", ints(place.position().getX(), place.position().getY(), place.position().getZ()));
                    entry.addProperty("distance", Math.round(distance * 10) / 10.0);
                    refs.add(entry);
                }
                JsonObject result = new JsonObject();
                result.add("references", refs);
                return GSON.toJson(result);
            }).join();
        }

        private String checkConstraints(JsonObject args) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > MAX_REGION_VOLUME) {
                return error("Region too large (" + volume + " blocks, max " + MAX_REGION_VOLUME + "); narrow the range");
            }

            return mc.submit(() -> {
                if (mc.level == null || mc.player == null) return error("No player or world loaded");
                boolean creative = mc.player.getAbilities().instabuild;
                boolean water = false, lava = false, air = true, bedrock = false;
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
                            if (!state.isAir()) air = false;
                            if (!state.getFluidState().isEmpty()) {
                                if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) lava = true;
                                else water = true;
                            }
                            if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) bedrock = true;
                        }
                    }
                }
                int surfaceHeight = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, minX, minZ);
                boolean underground = maxY < surfaceHeight;

                JsonArray warnings = new JsonArray();
                if (water) warnings.add("region contains water");
                if (lava) warnings.add("region contains lava");
                if (bedrock) warnings.add("region touches bedrock");
                if (underground) warnings.add("region is underground");
                if (!creative) warnings.add("survival mode: placed blocks must come from inventory, not synthesized");

                JsonObject result = new JsonObject();
                result.addProperty("accessible", true);
                result.addProperty("gamemode", creative ? "creative" : "survival");
                result.addProperty("air_only", air);
                result.addProperty("underground", underground);
                result.add("warnings", warnings);
                return GSON.toJson(result);
            }).join();
        }

        private static Block matchBlock(String needle) {
            String clean = normalize(needle);
            Block exact = null, contains = null;
            for (Block block : ForgeRegistries.BLOCKS.getValues()) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                if (id == null) continue;
                String path = normalize(id.getPath());
                if (path.equals(clean)) { exact = block; break; }
                if (contains == null && path.contains(clean)) contains = block;
            }
            return exact != null ? exact : contains;
        }

        private static String facing(float yaw) {
            String[] dirs = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
            int index = Math.floorMod(Math.round(yaw / 45.0f), 8);
            return dirs[index];
        }

        private static String itemId(ItemStack stack) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return id == null ? "unknown" : id.getPath();
        }

        private static String blockId(Block block) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            return id == null ? "unknown" : id.getPath();
        }

        private static String normalize(String value) {
            return value.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        }

        private static JsonArray ints(int... values) {
            JsonArray array = new JsonArray();
            for (int v : values) array.add(v);
            return array;
        }

        private static String error(String message) {
            JsonObject error = new JsonObject();
            error.addProperty("error", message);
            return GSON.toJson(error);
        }
    }
}
