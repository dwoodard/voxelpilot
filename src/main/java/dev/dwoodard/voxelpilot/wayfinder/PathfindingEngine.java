package dev.dwoodard.voxelpilot.wayfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class PathfindingEngine {
    private static final int MAX_PATH_LENGTH = 5000;
    private static final int MAX_ITERATIONS = 10000;
    private static final float STRAIGHT_LINE_COST = 1.4f;
    private static final float VERTICAL_COST = 2.0f;

    private PathfindingEngine() {}

    public static Optional<List<BlockPos>> findPath(Minecraft mc, BlockPos start, BlockPos goal, int maxDistance) {
        if (mc.level == null || start.distManhattan(goal) > maxDistance) {
            return Optional.empty();
        }

        if (!canStandAt(mc, start)) {
            System.out.println("[VoxelPilot] A* failed: start position not walkable");
            return Optional.empty();
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, Node> nodeMap = new HashMap<>();

        Node startNode = new Node(start, null, 0, heuristic(start, goal));
        openSet.offer(startNode);
        nodeMap.put(start, startNode);

        int iterations = 0;
        Node furthestNode = startNode;
        float furthestH = startNode.h;

        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            Node current = openSet.poll();

            if (current.pos.equals(goal)) {
                System.out.println("[VoxelPilot] A* success: reached goal in " + iterations + " iterations");
                return Optional.of(reconstructPath(current));
            }

            if (current.h < furthestH) {
                furthestH = current.h;
                furthestNode = current;
            }

            closedSet.add(current.pos);
            List<BlockPos> neighbors = getWalkableNeighbors(mc, current.pos, goal);

            for (BlockPos neighbor : neighbors) {
                if (closedSet.contains(neighbor)) continue;

                float tentativeG = current.g + cost(current.pos, neighbor);
                Node existing = nodeMap.get(neighbor);

                if (existing != null && tentativeG >= existing.g) continue;

                float h = heuristic(neighbor, goal);
                Node neighborNode = new Node(neighbor, current, tentativeG, h);
                nodeMap.put(neighbor, neighborNode);
                openSet.offer(neighborNode);
            }
        }

        if (furthestNode != startNode && furthestNode.h < startNode.h) {
            System.out.println("[VoxelPilot] A* partial: waypoint found (distance to goal: " + Math.round(furthestNode.h) + " blocks)");
            return Optional.of(reconstructPath(furthestNode));
        }

        System.out.println("[VoxelPilot] A* failed: no reachable waypoint. Explored: " + closedSet.size() + " nodes");
        return Optional.empty();
    }

    private static List<BlockPos> getWalkableNeighbors(Minecraft mc, BlockPos pos, BlockPos goal) {
        List<BlockPos> neighbors = new ArrayList<>();
        if (mc.level == null) return neighbors;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;

                BlockPos horizontal = pos.offset(dx, 0, dz);
                if (canStandAt(mc, horizontal)) {
                    neighbors.add(horizontal);
                    continue;
                }

                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos up = pos.offset(dx, dy, dz);
                    if (canStandAt(mc, up) && canReach(mc, pos, up)) {
                        neighbors.add(up);
                        break;
                    }
                }

                for (int dy = -1; dy >= -3; dy--) {
                    BlockPos down = pos.offset(dx, dy, dz);
                    if (canStandAt(mc, down) && canReach(mc, pos, down)) {
                        neighbors.add(down);
                        break;
                    }
                }
            }
        }

        for (int dy = -1; dy >= -2; dy--) {
            BlockPos down = pos.offset(0, dy, 0);
            if (canStandAt(mc, down) && canReach(mc, pos, down)) {
                neighbors.add(down);
                break;
            }
        }

        return neighbors;
    }

    private static boolean canStandAt(Minecraft mc, BlockPos feet) {
        if (mc.level == null) return false;
        BlockPos floor = feet.below();
        BlockPos head = feet.above();

        BlockState floorState = mc.level.getBlockState(floor);
        BlockState feetState = mc.level.getBlockState(feet);
        BlockState headState = mc.level.getBlockState(head);

        if (floorState.getCollisionShape(mc.level, floor).isEmpty()) return false;
        if (!feetState.getCollisionShape(mc.level, feet).isEmpty()) return false;
        if (!headState.getCollisionShape(mc.level, head).isEmpty()) return false;

        return true;
    }

    private static boolean canReach(Minecraft mc, BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());

        return dy <= 2 && (dx + dz) <= 1;
    }

    private static float cost(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();

        float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
        float vertical = Math.abs(dy) * VERTICAL_COST;

        return horizontal * STRAIGHT_LINE_COST + vertical;
    }

    private static float heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());

        return (dx + dz) * 1.0f + dy * 2.0f;
    }

    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        Node current = node;
        while (current != null) {
            path.add(0, current.pos);
            current = current.parent;
        }
        return path.subList(0, Math.min(path.size(), MAX_PATH_LENGTH));
    }

    private static class Node implements Comparable<Node> {
        BlockPos pos;
        Node parent;
        float g;
        float h;

        Node(BlockPos pos, Node parent, float g, float h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
        }

        float f() {
            return g + h;
        }

        @Override
        public int compareTo(Node other) {
            return Float.compare(this.f(), other.f());
        }
    }
}
