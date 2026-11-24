package com.extantape.waycave;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

/**
 * Copies a {@link CaveShape} into a world: the carved air, a closed shell of stone round it, a moss floor, glowing berry
 * vines for light and the odd plant. It's the only place this plugin sets blocks, apart from a chamber's own decoration
 * being part of the same job. It works a slice of columns every tick so a big cave never stalls the server, holds the
 * chunks it needs with plugin tickets while it works, and sets blocks without physics so nothing floods or pops off.
 *
 * <p>Building again over an existing cave sets the same blocks again, so a cave that has grown a new chamber keeps
 * every old one as it was.
 */
final class CaveBuilder {
    /** Columns copied per tick. About ten thousand block changes, which the server takes in its stride. */
    private static final int COLUMNS_PER_TICK = 450;
    /** How far past the open ground the stone shell reaches, in columns. */
    private static final int SHELL = 2;

    private final WaycavePlugin plugin;
    private BukkitTask task;

    CaveBuilder(WaycavePlugin plugin) {
        this.plugin = plugin;
    }

    /** Builds the shape with its base level (floor height 0) at oy, and plan coordinate (0, 0) at (ox, oz). */
    CompletableFuture<Void> build(World world, CaveShape shape, int ox, int oy, int oz) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int cx0 = (ox + shape.minX - 4) >> 4, cx1 = (ox + shape.minX + shape.width + 4) >> 4;
        int cz0 = (oz + shape.minZ - 4) >> 4, cz1 = (oz + shape.minZ + shape.depth + 4) >> 4;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) loads.add(world.getChunkAtAsync(cx, cz, true));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                done.completeExceptionally(error);
                return;
            }
            List<Chunk> held = new ArrayList<>();
            for (CompletableFuture<Chunk> load : loads) {
                Chunk chunk = load.getNow(null);
                if (chunk != null && chunk.addPluginChunkTicket(plugin)) held.add(chunk);
            }
            int[] next = {0};
            int total = (shape.width + 2 * SHELL) * (shape.depth + 2 * SHELL);
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    int end = Math.min(total, next[0] + COLUMNS_PER_TICK);
                    for (; next[0] < end; next[0]++) {
                        int x = shape.minX - SHELL + next[0] / (shape.depth + 2 * SHELL), z = shape.minZ - SHELL + next[0] % (shape.depth + 2 * SHELL);
                        column(world, shape, ox, oy, oz, x, z);
                    }
                    if (next[0] >= total) {
                        task.cancel();
                        held.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                        done.complete(null);
                    }
                } catch (RuntimeException e) {
                    task.cancel();
                    held.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                    done.completeExceptionally(e);
                }
            }, 1L, 1L);
        }));
        return done;
    }

    void cancel() {
        if (task != null) task.cancel();
    }

    // --- one column ---

    private static final BlockData STONE = Material.STONE.createBlockData(), TUFF = Material.TUFF.createBlockData(),
        MOSS = Material.MOSS_BLOCK.createBlockData(), DRIPSTONE = Material.DRIPSTONE_BLOCK.createBlockData(),
        ROOTED = Material.ROOTED_DIRT.createBlockData(), CLAY = Material.CLAY.createBlockData(),
        AMETHYST = Material.AMETHYST_BLOCK.createBlockData(), AIR = Material.AIR.createBlockData(),
        WATER = Material.WATER.createBlockData(), SPORE = Material.SPORE_BLOSSOM.createBlockData(),
        AZALEA = Material.AZALEA.createBlockData(), FLOWERING = Material.FLOWERING_AZALEA.createBlockData(),
        DRIPLEAF = Bukkit.createBlockData("minecraft:big_dripleaf[facing=north]"),
        VINE_TIP = Bukkit.createBlockData("minecraft:cave_vines[age=25,berries=true]"), VINE = Bukkit.createBlockData("minecraft:cave_vines_plant[berries=true]"),
        FERN = Material.FERN.createBlockData(), GRASS = Material.SHORT_GRASS.createBlockData();
    /** Glow lichen growing on the east, west, south and north wall. */
    private static final BlockData[] LICHEN = {Bukkit.createBlockData("minecraft:glow_lichen[east=true]"), Bukkit.createBlockData("minecraft:glow_lichen[west=true]"),
        Bukkit.createBlockData("minecraft:glow_lichen[south=true]"), Bukkit.createBlockData("minecraft:glow_lichen[north=true]")};

    private static BlockData wall(long salt, int x, int y, int z) {
        int r = hash(salt, x, y, z) % 1000;
        if (r < 340) return STONE;
        if (r < 590) return TUFF;
        if (r < 740) return MOSS;
        if (r < 840) return DRIPSTONE;
        if (r < 920) return ROOTED;
        if (r < 970) return CLAY;
        return r < 985 ? AMETHYST : STONE;
    }

    private static int hash(long salt, int x, int y, int z) {
        long h = salt ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 31;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) ((h >>> 1) % 1_000_000_007L) & 0x7fffffff;
    }

    private void column(World world, CaveShape shape, int ox, int oy, int oz, int x, int z) {
        long salt = shape.plan.seed();
        int wx = ox + x, wz = oz + z;
        if (shape.open(x, z)) {
            int f = shape.floorAt(x, z), top = shape.topAt(x, z);
            byte kind = shape.kindAt(x, z);
            boolean pool = kind == CaveShape.POOL;
            // Below the feet: a moss floor (a pool's floor is clay with water on it), stone under that.
            set(world, wx, oy + f - 1, wz, pool ? WATER : (hash(salt, x, 0, z) % 10 == 0 ? ROOTED : MOSS));
            set(world, wx, oy + f - 2, wz, pool ? CLAY : STONE);
            set(world, wx, oy + f - 3, wz, STONE);
            // Air, then the plants and vines on top of it.
            int vine = shape.vineBottomAt(x, z);
            for (int y = f; y <= top; y++) set(world, wx, oy + y, wz, AIR);
            if (kind == CaveShape.BLOCKED) {
                int pick = hash(salt, x, 7, z) % 10;
                set(world, wx, oy + f, wz, pick < 4 ? AZALEA : pick < 7 ? FLOWERING : DRIPLEAF);
            } else if (kind == CaveShape.WALK && vine < 0) {
                int r = hash(salt, x, 3, z) % 100;
                if (r < 5) set(world, wx, oy + f, wz, FERN);
                else if (r < 11) set(world, wx, oy + f, wz, GRASS);
            }
            if (vine >= 0) {
                for (int y = top; y > vine; y--) set(world, wx, oy + y, wz, VINE);
                set(world, wx, oy + vine, wz, VINE_TIP);
            } else if (kind != CaveShape.POOL && hash(salt, x, 5, z) % 100 < 3) {
                set(world, wx, oy + top, wz, SPORE);
            }
            // Glow lichen on the face of a wall that looks into this column, here and there.
            for (int[] side : new int[][] {{1, 0, 0}, {-1, 0, 1}, {0, 1, 2}, {0, -1, 3}}) {
                if (shape.open(x + side[0], z + side[1]) || vine >= 0) continue;
                for (int y = f + 1; y <= top; y++) {
                    if (hash(salt, x * 4 + side[2], y, z) % 100 < 6) set(world, wx, oy + y, wz, LICHEN[side[2]]);
                }
            }
            // The ceiling.
            for (int y = top + 1; y <= top + 3; y++) set(world, wx, oy + y, wz, wall(salt, x, y, z));
            return;
        }
        // A wall column: solid across the height of every open column within the shell's reach.
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int dx = -SHELL; dx <= SHELL; dx++) {
            for (int dz = -SHELL; dz <= SHELL; dz++) {
                if (shape.open(x + dx, z + dz)) {
                    low = Math.min(low, shape.floorAt(x + dx, z + dz) - 3);
                    high = Math.max(high, shape.topAt(x + dx, z + dz) + 3);
                }
            }
        }
        if (low > high) return;
        for (int y = low; y <= high; y++) set(world, wx, oy + y, wz, wall(salt, x, y, z));
    }

    private static void set(World world, int x, int y, int z, BlockData data) {
        Block block = world.getBlockAt(x, y, z);
        if (!block.getBlockData().equals(data)) block.setBlockData(data, false);
    }
}
