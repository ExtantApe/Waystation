package com.extantape.waystation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;

/**
 * Finds places a gate can stand. Read-only: nothing here, or anywhere in this plugin, sets a block.
 *
 * Callers must have loaded the chunks first. A candidate's checked area always stays inside the
 * candidate's own chunk, so a search can't trigger a surprise synchronous chunk load.
 */
final class SafeSpots {
    enum Problem { NOT_EXPLORED, OUTSIDE_BORDER, CLAIMED, NO_SPOT }
    record Spot(int x, int y, int z) {}
    record Result(Spot spot, Problem problem) {}

    /** Footprint margins around the gate's centre block, for walking room and the frame. */
    private static final int ALONG = 2, ACROSS = 1;
    /** Liquid must be further than this from the footprint. */
    private static final int LIQUID_MARGIN = 1;

    private static final Set<Material> BAD_GROUND = Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.BEDROCK, Material.STONECUTTER, Material.SCAFFOLDING);
    private static final Set<Material> BAD_SPACE = Set.of(Material.FIRE, Material.SOUL_FIRE, Material.COBWEB,
        Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.NETHER_PORTAL,
        Material.END_PORTAL, Material.END_GATEWAY);
    private static final Set<Material> WATER_PLANTS = Set.of(Material.KELP, Material.KELP_PLANT, Material.SEAGRASS,
        Material.TALL_SEAGRASS, Material.BUBBLE_COLUMN, Material.WATER, Material.LAVA);

    private static final Set<Material> BUILT = Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.LECTERN,
        Material.FLOWER_POT, Material.DECORATED_POT, Material.ENCHANTING_TABLE, Material.BREWING_STAND, Material.CRAFTING_TABLE,
        Material.FURNACE, Material.TORCH, Material.WALL_TORCH, Material.LANTERN, Material.GLASS, Material.FARMLAND, Material.LADDER);

    private final Claims claims;

    SafeSpots(Claims claims) {
        this.claims = claims;
    }

    /** A prayer: the highest safe spot in this loaded chunk, nearest the chunk's middle first. */
    Result inChunk(World world, int chunkX, int chunkZ, BlockFace facing, Settings.Look look, UUID claimant,
                   Collection<Gate> others, boolean avoidBuilds) {
        int inset = reach(look) + LIQUID_MARGIN;
        List<int[]> columns = new ArrayList<>();
        for (int dx = inset; dx < 16 - inset; dx++) {
            for (int dz = inset; dz < 16 - inset; dz++) columns.add(new int[] {dx, dz});
        }
        columns.sort(Comparator.comparingInt(c -> (c[0] - 8) * (c[0] - 8) + (c[1] - 8) * (c[1] - 8)));

        boolean sawClaim = false, sawBorder = false;
        int top = world.getMinHeight() + world.getLogicalHeight() - look.height() - 2;
        for (int[] c : columns) {
            int x = (chunkX << 4) + c[0], z = (chunkZ << 4) + c[1];
            if (!insideBorder(world, x, z, look)) {
                sawBorder = true;
                continue;
            }
            if (claimed(world, x, z, facing, look, claimant)) {
                sawClaim = true;
                continue;
            }
            for (int y = top; y > world.getMinHeight(); y--) {
                if (terrainOk(world, x, y, z, facing, look) && !nearOther(world, x, y, z, others)) {
                    // Near a build, try another column. Digging down this one would only find a cave beneath it.
                    if (avoidBuilds && nearBuild(world, x, y, z, chunkX, chunkZ)) break;
                    return new Result(new Spot(x, y, z), null);
                }
            }
        }
        if (sawClaim) return new Result(null, Problem.CLAIMED);
        if (sawBorder) return new Result(null, Problem.OUTSIDE_BORDER);
        return new Result(null, Problem.NO_SPOT);
    }

    /** Is a spot found earlier still good? Time passes while a tribute is being offered. */
    boolean stillGood(World world, Spot spot, BlockFace facing, Settings.Look look, UUID claimant, Collection<Gate> others) {
        return insideBorder(world, spot.x(), spot.z(), look) && !claimed(world, spot.x(), spot.z(), facing, look, claimant)
            && terrainOk(world, spot.x(), spot.y(), spot.z(), facing, look) && !nearOther(world, spot.x(), spot.y(), spot.z(), others);
    }

    /** Signs of someone's work close by, within this chunk. Rough on purpose: it only decides what to try first. */
    private static boolean nearBuild(World world, int x, int y, int z, int chunkX, int chunkZ) {
        int r = 6;
        for (int bx = Math.max(x - r, chunkX << 4); bx <= Math.min(x + r, (chunkX << 4) + 15); bx++) {
            for (int bz = Math.max(z - r, chunkZ << 4); bz <= Math.min(z + r, (chunkZ << 4) + 15); bz++) {
                for (int by = y - 2; by <= y + 6; by++) {
                    if (built(world.getBlockAt(bx, by, bz).getType())) return true;
                }
            }
        }
        return false;
    }

    private static boolean built(Material type) {
        return BUILT.contains(type) || Tag.BEDS.isTagged(type) || Tag.ALL_SIGNS.isTagged(type) || Tag.BANNERS.isTagged(type)
            || Tag.PLANKS.isTagged(type) || Tag.DOORS.isTagged(type) || Tag.WOOL.isTagged(type) || Tag.FENCES.isTagged(type)
            || Tag.STAIRS.isTagged(type) || Tag.SLABS.isTagged(type) || Tag.SHULKER_BOXES.isTagged(type);
    }

    /** Gate generation: the safe floor closest to the wanted level in this one column, or null. */
    Spot nearLevel(World world, int x, int wantedY, int z, BlockFace facing, Settings.Look look, Collection<Gate> others) {
        // Reading a block in an unloaded chunk would load it, and generate it if it's new. Never do that.
        int r = reach(look) + LIQUID_MARGIN;
        for (int dx : new int[] {-r, r}) {
            for (int dz : new int[] {-r, r}) {
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return null;
            }
        }
        int low = world.getMinHeight() + 1, high = world.getMinHeight() + world.getLogicalHeight() - look.height() - 2;
        for (int offset = 0; offset <= 12; offset++) {
            for (int y : new int[] {wantedY + offset, wantedY - offset}) {
                if (y >= low && y <= high && insideBorder(world, x, z, look)
                    && terrainOk(world, x, y, z, facing, look) && !nearOther(world, x, y, z, others)) {
                    return new Spot(x, y, z);
                }
            }
        }
        return null;
    }

    /** Can a gate stand with its feet in block y here? Solid floor, open air, and no liquid close by. */
    boolean terrainOk(World world, int x, int y, int z, BlockFace facing, Settings.Look look) {
        boolean alongX = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
        int alongReach = reach(look);
        // Solid floor under the whole footprint.
        for (int a = -alongReach; a <= alongReach; a++) {
            for (int b = -ACROSS; b <= ACROSS; b++) {
                Block floor = world.getBlockAt(x + (alongX ? a : b), y - 1, z + (alongX ? b : a));
                Material type = floor.getType();
                if (!type.isSolid() || floor.isPassable() || BAD_GROUND.contains(type) || Tag.LEAVES.isTagged(type)) return false;
            }
        }
        // Open space for the gate and for people to walk through it.
        for (int a = -alongReach; a <= alongReach; a++) {
            for (int b = -ACROSS; b <= ACROSS; b++) {
                for (int up = 0; up <= look.height(); up++) {
                    Block space = world.getBlockAt(x + (alongX ? a : b), y + up, z + (alongX ? b : a));
                    if (!space.isPassable() || BAD_SPACE.contains(space.getType())) return false;
                }
            }
        }
        // No liquid in or right next to any of it, including under the floor.
        int r = alongReach + LIQUID_MARGIN, s = ACROSS + LIQUID_MARGIN;
        for (int a = -r; a <= r; a++) {
            for (int b = -s; b <= s; b++) {
                for (int up = -2; up <= look.height() + 1; up++) {
                    if (wet(world.getBlockAt(x + (alongX ? a : b), y + up, z + (alongX ? b : a)))) return false;
                }
            }
        }
        return true;
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * Every column a person can walk to from this spot by stepping up at most one block and dropping
     * at most maxDrop, through two-block-high space. Doors count as open. Water counts only if swimming
     * is allowed, for spawns on islands; a gate itself is never placed in water. Loaded chunks only.
     */
    Map<Long, Integer> walkableFrom(World world, Location from, int radius, boolean swim, int maxDrop) {
        Map<Long, Integer> seen = new HashMap<>();
        int startY = from.getBlockY();
        while (startY > world.getMinHeight() + 1 && !walkable(world, from.getBlockX(), startY, from.getBlockZ(), swim)) startY--;
        if (startY <= world.getMinHeight() + 1) return seen;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {from.getBlockX(), startY, from.getBlockZ()});
        seen.put(key(from.getBlockX(), from.getBlockZ()), startY);
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty() && seen.size() < 40000) {
            int[] at = queue.poll();
            for (int[] step : steps) {
                int x = at[0] + step[0], z = at[2] + step[1];
                if (Math.abs(x - from.getBlockX()) > radius || Math.abs(z - from.getBlockZ()) > radius) continue;
                if (seen.containsKey(key(x, z)) || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = at[1] + 1; y >= at[1] - maxDrop; y--) {
                    if (walkable(world, x, y, z, swim)) {
                        seen.put(key(x, z), y);
                        queue.add(new int[] {x, y, z});
                        break;
                    }
                }
            }
        }
        return seen;
    }

    private static boolean walkable(World world, int x, int y, int z, boolean swim) {
        Block feet = world.getBlockAt(x, y, z), head = world.getBlockAt(x, y + 1, z);
        if (swim && feet.getType() == Material.WATER) return head.isPassable() && head.getType() != Material.LAVA;
        if (!Tag.DOORS.isTagged(feet.getType()) && !Tag.FENCE_GATES.isTagged(feet.getType())) return standable(world, x, y, z);
        return world.getBlockAt(x, y - 1, z).getType().isSolid();
    }

    /** Solid ground below, room for feet and head, nothing wet or harmful: somewhere to put a player. */
    static boolean safeToStand(org.bukkit.Location at) {
        return at.getWorld() != null && standable(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private static boolean standable(World world, int x, int y, int z) {
        Block floor = world.getBlockAt(x, y - 1, z), feet = world.getBlockAt(x, y, z), head = world.getBlockAt(x, y + 1, z);
        return floor.getType().isSolid() && !floor.isPassable() && !BAD_GROUND.contains(floor.getType())
            && feet.isPassable() && head.isPassable() && !wet(feet) && !wet(head) && !BAD_SPACE.contains(feet.getType());
    }

    /** Three blocks of level-ish ground in front of and behind a gate, so walking through is just walking. */
    boolean firmBothSides(World world, Spot spot, BlockFace facing) {
        int fx = facing.getModX(), fz = facing.getModZ();
        for (int side : new int[] {-1, 1}) {
            for (int d = 2; d <= 4; d++) {
                int x = spot.x() + fx * side * d, z = spot.z() + fz * side * d;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                boolean firm = false;
                for (int y = spot.y() + 1; y >= spot.y() - 1; y--) firm |= standable(world, x, y, z);
                if (!firm) return false;
            }
        }
        return true;
    }

    private static boolean wet(Block block) {
        return WATER_PLANTS.contains(block.getType()) || block.isLiquid()
            || (block.getBlockData() instanceof Waterlogged w && w.isWaterlogged());
    }

    private static int reach(Settings.Look look) {
        return Math.max(ALONG, look.width() / 2 + 1);
    }

    boolean insideBorder(World world, int x, int z, Settings.Look look) {
        WorldBorder border = world.getWorldBorder();
        int r = reach(look) + LIQUID_MARGIN;
        return border.isInside(new Location(world, x - r, 64, z - r)) && border.isInside(new Location(world, x + r, 64, z - r))
            && border.isInside(new Location(world, x - r, 64, z + r)) && border.isInside(new Location(world, x + r, 64, z + r));
    }

    private boolean claimed(World world, int x, int z, BlockFace facing, Settings.Look look, UUID claimant) {
        boolean alongX = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
        int along = reach(look);
        for (int a = -along; a <= along; a++) {
            for (int b = -ACROSS; b <= ACROSS; b++) {
                Location at = new Location(world, x + (alongX ? a : b), 64, z + (alongX ? b : a));
                if (claims.foreignClaim(at, claimant)) return true;
            }
        }
        return false;
    }

    private static boolean nearOther(World world, int x, int y, int z, Collection<Gate> others) {
        for (Gate p : others) {
            if (p.world.equals(world.getName()) && Math.abs(p.x - x) <= 6 && Math.abs(p.z - z) <= 6 && Math.abs(p.y - y) <= 6) {
                return true;
            }
        }
        return false;
    }
}
