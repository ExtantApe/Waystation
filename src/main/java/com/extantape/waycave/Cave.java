package com.extantape.waycave;

import com.extantape.waycave.CaveLayout.Plan;
import com.extantape.waycave.CaveLayout.Socket;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The cave this server has: its saved plan, its built shape and where it stands in the world. The plan lives in cave.yml
 * and is grown, never rewritten: a group added later gets a new chamber and nothing already planned moves. If cave.yml
 * can't be read it is never overwritten, since a new cave in its place would move every gate.
 */
final class Cave {
    /** Bump when the builder changes what it builds, so existing caves are built again. */
    static final int BUILD_VERSION = 1;

    private final WaycavePlugin plugin;
    private final File file;
    private final CaveBuilder builder;
    private Plan plan;
    private CaveShape shape;
    private String built = "";
    private boolean readable = true;
    private boolean building;

    Cave(WaycavePlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
        this.builder = new CaveBuilder(plugin);
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
            if (y.getConfigurationSection("plan") == null) throw new IllegalArgumentException("cave.yml has no plan");
            plan = CaveLayout.fromMap(deep(y.getConfigurationSection("plan")));
            built = y.getString("built", "");
            shape = null;
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            readable = false;
            plugin.getLogger().log(Level.SEVERE, "cave.yml can't be read, so the cave won't be changed until it's fixed", e);
        }
    }

    /** A saved section as plain maps and lists, which is what {@link CaveLayout#fromMap} reads. */
    private static Map<String, Object> deep(org.bukkit.configuration.ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object v = section.get(key);
            out.put(key, v instanceof org.bukkit.configuration.ConfigurationSection s ? deep(s) : v);
        }
        return out;
    }

    private boolean save() {
        if (!readable) return false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("plan", CaveLayout.toMap(plan));
        y.set("built", built);
        try {
            GateStore.writeAtomically(file, y.saveToString());
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't save cave.yml", e);
            return false;
        }
    }

    boolean exists() {
        return plan != null;
    }

    Plan plan() {
        return plan;
    }

    CaveShape shape() {
        if (plan == null) return null;
        if (shape == null) shape = new CaveShape(plan);
        return shape;
    }

    // --- where it stands ---

    int originX() {
        Settings s = plugin.settings();
        return s.stationCenterX != null ? s.stationCenterX : 0;
    }

    int originZ() {
        Settings s = plugin.settings();
        return s.stationCenterZ != null ? s.stationCenterZ : 0;
    }

    int originY() {
        return plugin.settings().stationY;
    }

    /** A socket as a spot in the world: the block the gate's feet stand in. */
    int[] worldSpot(Socket socket) {
        return new int[] {originX() + socket.x(), originY() + shape().floorAt(socket.x(), socket.z()), originZ() + socket.z()};
    }

    /** The start chamber's spawn: north of the monument, looking at it. */
    Location spawn(World world) {
        CaveShape s = shape();
        int x = 0, z = -9;
        while (z > -13 && !s.walkable(x, z)) z--;
        Location at = new Location(world, originX() + x + 0.5, originY() + s.floorAt(x, z), originZ() + z + 0.5);
        at.setYaw(0); // south, at the monument
        return at;
    }

    /** The middle of the start chamber, in the world, at floor level. */
    Location middle(World world) {
        return new Location(world, originX() + 0.5, originY(), originZ() + 0.5);
    }

    // --- planning ---

    /**
     * Makes sure the plan exists and gives every group in {@code gates} a chamber. Returns the groups that couldn't get
     * one (no room left inside the border), for the caller to report. Saves when anything changed.
     */
    List<String> plan(long seed, Map<String, Integer> gates) {
        List<String> noRoom = new ArrayList<>();
        if (!readable) return noRoom;
        boolean changed = false;
        if (plan == null) {
            plan = CaveLayout.fresh(seed);
            changed = true;
        }
        for (Map.Entry<String, Integer> e : gates.entrySet()) {
            if (plan.ofGroup(e.getKey()) != null) continue;
            Plan grown = CaveLayout.with(plan, e.getKey(), e.getValue());
            if (grown == plan) {
                noRoom.add(e.getKey());
                continue;
            }
            plan = grown;
            changed = true;
        }
        if (changed) {
            shape = null;
            save();
        }
        return noRoom;
    }

    // --- building ---

    /** What the world should look like for this plan and this builder: changes whenever either does. */
    private String marker() {
        return BUILD_VERSION + ":" + originX() + "," + originY() + "," + originZ() + ":" + Integer.toHexString(CaveLayout.toMap(plan).toString().hashCode());
    }

    boolean needsBuild() {
        return plan != null && readable && (!marker().equals(built));
    }

    /**
     * Builds the cave into the world if the plan or the builder has changed since it was last built, then checks the
     * real blocks: every chamber's middle, every tunnel and the front of every gate socket must be walkable from the
     * start. Completes with the problems found. A cave that fails the check is left alone for an admin, and no gate is
     * placed in it.
     */
    CompletableFuture<List<String>> ensureBuilt(World world) {
        List<String> problems = new ArrayList<>();
        if (plan == null || !readable) return CompletableFuture.completedFuture(problems);
        CaveShape s = shape();
        List<String> flaws = s.unreachable();
        if (!flaws.isEmpty()) {
            problems.add("The cave plan has a flaw, so it wasn't built: " + String.join("; ", flaws));
            return CompletableFuture.completedFuture(problems);
        }
        if (!needsBuild() || building) return CompletableFuture.completedFuture(problems);
        building = true;
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        plugin.getLogger().info("Building the cave: " + plan.chambers().size() + " chambers, " + plan.tunnels().size() + " tunnels.");
        builder.build(world, s, originX(), originY(), originZ()).whenComplete((v, error) -> {
            building = false;
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Building the cave failed", error);
                problems.add("Building the cave failed: " + error.getMessage());
                done.complete(problems);
                return;
            }
            plugin.worlds().setSpawn(world, spawn(world));
            problems.addAll(verify(world));
            if (problems.isEmpty()) {
                built = marker();
                save();
                plugin.getLogger().info("The cave is built and every chamber and gate can be walked to.");
            }
            done.complete(problems);
        });
        return done;
    }

    /** Walks the real blocks from the start chamber, the way a player would. Empty is good. */
    List<String> verify(World world) {
        List<String> problems = new ArrayList<>();
        CaveShape s = shape();
        Location start = middle(world);
        start.setY(originY() + s.floorAt(0, 0));
        Map<Long, Integer> reach = plugin.safeSpots().walkableFrom(world, start, 150, false, 2);
        if (reach.isEmpty()) return List.of("Nobody can stand in the start chamber.");
        for (CaveLayout.Chamber c : plan.chambers()) {
            if (!reach.containsKey(SafeSpots.key(originX() + c.x(), originZ() + c.z()))) problems.add("Chamber " + c.id() + " can't be walked to.");
        }
        for (Socket socket : plan.sockets()) {
            int[] f = CaveShape.frontOf(socket);
            if (!reach.containsKey(SafeSpots.key(originX() + f[0], originZ() + f[1]))) problems.add("The front of the gate place at " + socket.x() + " " + socket.z() + " can't be walked to.");
        }
        if (!problems.isEmpty()) plugin.getLogger().severe("The built cave failed its walk-through: " + String.join(" ", problems));
        return problems;
    }

    void stop() {
        builder.cancel();
    }
}
