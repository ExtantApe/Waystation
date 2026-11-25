package com.extantape.waycave;

import com.extantape.waycave.CaveLayout.Socket;
import com.extantape.waycave.TrailPath.Cell;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The trails each player sees in the cave (D194): a worn track of moss carpet edged with glow lichen from the start
 * chamber to every gate that player can use, and to no other. They are fake blocks sent to that one player, laid over
 * the real floor, so another player on the same spot sees plain floor, and nothing is ever placed in the world.
 *
 * <p>They're redrawn when the player's access changes (a gate they can't use loses its trail within a second), resent
 * when a chunk is sent to them again, and taken back when they leave the cave. The shape of each trail is
 * {@link TrailRoute}'s, tested without a server.
 */
final class Trails implements Listener {
    private static final BlockData CARPET = Bukkit.createBlockData("minecraft:moss_carpet");
    private static final BlockData LICHEN = Bukkit.createBlockData("minecraft:glow_lichen[down=true]");

    /** What one player has been shown: block position key to the fake block, and a summary of why. */
    private record Shown(String key, Map<Long, Pos> blocks) {}

    private record Pos(int x, int y, int z, BlockData data) {}

    private final WaycavePlugin plugin;
    private final Map<UUID, Shown> shown = new HashMap<>();

    Trails(WaycavePlugin plugin) {
        this.plugin = plugin;
    }

    private World cave() {
        return Bukkit.getWorld(plugin.settings().stationWorld);
    }

    /** The once-a-second pass: everyone in the cave has the trails they should, and nobody else has any. */
    void pass() {
        World world = cave();
        for (UUID id : new ArrayList<>(shown.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || world == null || !p.getWorld().equals(world) || !wanted()) drop(id, p);
        }
        if (world == null || !wanted()) return;
        for (Player p : world.getPlayers()) refresh(p, false);
    }

    private boolean wanted() {
        return plugin.settings().trails && plugin.cave().exists() && plugin.cave().shape() != null;
    }

    void refreshAll() {
        for (UUID id : new ArrayList<>(shown.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) drop(id, p);
        }
    }

    /** The gates this player can use in the cave, in a steady order. */
    List<Gate> usable(Player p) {
        List<Gate> out = new ArrayList<>();
        for (Gate g : plugin.store().inWorld(plugin.settings().stationWorld)) {
            if (plugin.canEnter(p, g)) out.add(g);
        }
        out.sort(java.util.Comparator.comparing(g -> g.id));
        return out;
    }

    /** The socket a gate stands at, or null for a gate placed by hand somewhere else. */
    Socket socketOf(Gate g) {
        Cave cave = plugin.cave();
        for (Socket s : cave.plan().sockets()) {
            int[] at = cave.worldSpot(s);
            if (at[0] == g.x && at[2] == g.z) return s;
        }
        return null;
    }

    /** Draws this player's trails, or changes them to match what they can use now. Does nothing if nothing changed. */
    void refresh(Player p, boolean force) {
        World world = cave();
        if (world == null || !p.getWorld().equals(world) || !wanted()) return;
        List<Gate> gates = usable(p);
        StringBuilder key = new StringBuilder();
        for (Gate g : gates) key.append(g.id).append('@').append(g.x).append(',').append(g.z).append(';');
        Shown before = shown.get(p.getUniqueId());
        if (!force && before != null && before.key().equals(key.toString())) return;

        Cave cave = plugin.cave();
        CaveShape shape = cave.shape();
        long seed = playerSeed(p);
        Map<Long, Pos> wanted = new LinkedHashMap<>();
        for (Gate g : gates) {
            Socket socket = socketOf(g);
            if (socket == null) continue;
            List<Cell> trail = TrailRoute.trail(shape, socket, seed);
            for (Cell c : TrailRoute.edging(shape, trail, seed ^ 0x51)) put(wanted, cave, shape, c, LICHEN);
            for (Cell c : trail) put(wanted, cave, shape, c, CARPET);
        }
        // Whatever was shown and isn't wanted now goes back to the real block, then the new ones are drawn.
        if (before != null) {
            List<BlockState> undo = new ArrayList<>();
            for (var e : before.blocks().entrySet()) {
                if (wanted.containsKey(e.getKey())) continue;
                Pos pos = e.getValue();
                if (loaded(world, pos)) undo.add(world.getBlockAt(pos.x(), pos.y(), pos.z()).getState());
            }
            if (!undo.isEmpty()) p.sendBlockChanges(undo);
        }
        shown.put(p.getUniqueId(), new Shown(key.toString(), wanted));
        send(p, world, wanted.values());
    }

    private void put(Map<Long, Pos> out, Cave cave, CaveShape shape, Cell c, BlockData data) {
        int x = cave.originX() + c.x(), z = cave.originZ() + c.z(), y = cave.originY() + shape.floorAt(c.x(), c.z());
        out.put(SafeSpots.key(x, z), new Pos(x, y, z, data));
    }

    private static boolean loaded(World world, Pos pos) {
        return world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    private void send(Player p, World world, java.util.Collection<Pos> blocks) {
        List<BlockState> states = new ArrayList<>();
        for (Pos pos : blocks) {
            if (!loaded(world, pos)) continue; // sent when its chunk reaches the player
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            BlockState state = block.getState();
            state.setBlockData(pos.data());
            states.add(state);
        }
        if (!states.isEmpty()) p.sendBlockChanges(states);
    }

    private long playerSeed(Player p) {
        UUID id = p.getUniqueId();
        return id.getMostSignificantBits() * 31 + id.getLeastSignificantBits() + plugin.cave().plan().seed();
    }

    private void drop(UUID id, Player p) {
        Shown before = shown.remove(id);
        if (before == null || p == null || !p.isOnline()) return;
        World world = p.getWorld();
        List<BlockState> undo = new ArrayList<>();
        World cave = cave();
        if (cave == null) return;
        for (Pos pos : before.blocks().values()) if (loaded(cave, pos)) undo.add(cave.getBlockAt(pos.x(), pos.y(), pos.z()).getState());
        // Only if they're still in the cave: leaving it replaced every chunk they saw, fake blocks included.
        if (!undo.isEmpty() && world.equals(cave)) p.sendBlockChanges(undo);
    }

    /** A chunk reaching the player carries the real blocks, so the trails in it are drawn over them again. */
    @EventHandler
    public void onChunk(PlayerChunkLoadEvent event) {
        Player p = event.getPlayer();
        Shown s = shown.get(p.getUniqueId());
        if (s == null || !event.getWorld().equals(cave())) return;
        int cx = event.getChunk().getX(), cz = event.getChunk().getZ();
        List<Pos> here = new ArrayList<>();
        for (Pos pos : s.blocks().values()) if ((pos.x() >> 4) == cx && (pos.z() >> 4) == cz) here.add(pos);
        if (here.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.getWorld().equals(event.getWorld())) send(p, event.getWorld(), here);
        }, 2L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(event.getPlayer(), true), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }

    /** For tests and /gate: the block positions of this player's trails, as "x y z" in order. */
    List<String> describe(Player p) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Pos pos : s.blocks().values()) out.add(pos.x() + " " + pos.y() + " " + pos.z() + " " + pos.data().getMaterial().getKey().getKey());
        return out;
    }
}
