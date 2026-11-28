package com.extantape.waycave;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.WorldType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The cave's gates: which world group each world is in, its chambers, and generating linked pairs. */
final class Station implements Listener {
    record Platform(String group, int number, String label, List<BlockData> theme, List<Gate> gates, String design, boolean worldBlocks, long seed) {}
    private record Slot(String world, int x, Integer y, int z, BlockFace facing) {}

    static final String UNGROUPED = "Other";

    private final WaycavePlugin plugin;
    private final Map<String, String> worldToGroup = new LinkedHashMap<>();
    private final Map<String, Platform> platforms = new LinkedHashMap<>();
    private boolean generating, syncAgain;
    /** Worlds we couldn't place. Only a reload or a world event tries them again. */
    private final Set<String> gaveUp = new TreeSet<>();
    private final Set<String> deadBefore = new TreeSet<>();
    /** Station gates with nowhere safe to stand. An admin decides what happens next. */
    private final List<String> blockedSlots = new ArrayList<>();

    Station(WaycavePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The station has a flat world of its own, made through Multiverse like any other. It's only made
     * once, and only if it doesn't exist, so a real world of that name is never touched.
     */
    void ensureWorld() {
        Settings s = plugin.settings();
        if (!plugin.worlds().managesWorlds()) {
            plugin.getLogger().info("Multiverse-Core isn't installed, so there's no station and no automatic waygates. "
                + "Fargates, gates made with /gate create and the return command still work.");
            return;
        }
        if (!known(s.stationWorld)) {
            if (!s.stationAutoCreate) {
                plugin.getLogger().warning("The station's world doesn't exist, and station.auto-create is off: " + s.stationWorld);
                return;
            }
            plugin.worlds().createStationWorld(s.stationWorld, s.stationY, () -> {});
        }
        World world = Bukkit.getWorld(s.stationWorld);
        if (world == null) return;
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0); // no fire spreads
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        int[] center = center(world);
        world.getWorldBorder().setCenter(center[0] + 0.5, center[1] + 0.5);
        world.getWorldBorder().setSize(s.stationBorder);
        loadGroups();
        if (!worldToGroup.containsKey(s.stationWorld) && new File(plugin.getDataFolder().getParentFile(), "Multiverse-Inventories/groups.yml").exists()) {
            plugin.getLogger().warning("The station's world isn't in a Multiverse-Inventories group. Add it to your lobby's group "
                + "so inventories and arrival spots behave: see setup/README in the source.");
        }
    }

    /** Multiverse-Inventories owns the groups. Read them from its own file, so there's one source. */
    void loadGroups() {
        worldToGroup.clear();
        File file = new File(plugin.getDataFolder().getParentFile(), "Multiverse-Inventories/groups.yml");
        if (!file.exists()) return;
        ConfigurationSection groups = YamlConfiguration.loadConfiguration(file).getConfigurationSection("groups");
        if (groups == null) return;
        for (String group : groups.getKeys(false)) {
            for (String world : groups.getStringList(group + ".worlds")) worldToGroup.put(world, group);
        }
    }

    String groupOf(String world) {
        String live = worldToGroup.get(world);
        if (live != null) return live;
        Gate gate = plugin.store().get("station-" + world);
        return gate != null && gate.group != null ? gate.group : UNGROUPED;
    }

    /** Which inventories this world uses, for keeping items where they belong. */
    String inventoryGroup(String world) {
        String group = worldToGroup.get(world);
        return group != null ? group : "world:" + world;
    }

    private boolean known(String world) {
        return plugin.worlds().known(world);
    }

    /** Worlds that should have gates now: listed ones first, then what Multiverse knows about. */
    List<String> liveWorlds() {
        Settings s = plugin.settings();
        Set<String> found = new LinkedHashSet<>();
        if (!plugin.worlds().managesWorlds()) return new ArrayList<>(); // no station, so no automatic waygates
        for (String world : s.worlds.keySet()) if (known(world)) found.add(world);
        if (s.autoWorlds) {
            Set<String> discovered = new TreeSet<>();
            for (String w : plugin.worlds().normalWorlds()) if (!s.excluded(w)) discovered.add(w);
            found.addAll(discovered);
        }
        found.remove(s.stationWorld);
        return new ArrayList<>(found);
    }

    /** Each group's worlds on the station, in order, including dead gates, leaving out gates an admin removed. */
    private Map<String, List<String>> lineup() {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (String world : liveWorlds()) {
            boolean removedByAdmin = plugin.store().generated(world) && plugin.store().get("station-" + world) == null;
            if (!removedByAdmin) byGroup.computeIfAbsent(groupOf(world), k -> new ArrayList<>()).add(world);
        }
        for (Gate gate : plugin.store().inWorld(plugin.settings().stationWorld)) {
            if (gate.leavesServer()) continue; // placed by hand, on no platform
            List<String> line = byGroup.computeIfAbsent(groupOf(gate.destination), k -> new ArrayList<>());
            if (!line.contains(gate.destination)) line.add(gate.destination);
        }
        return byGroup;
    }

    /** Label from config, or the world's name tidied up. */
    String labelOf(String world) {
        Settings.WorldEntry entry = plugin.settings().worlds.get(world);
        return entry != null && !entry.label().equals(world) ? entry.label() : tidy(world);
    }

    String plainName(String world) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(labelOf(world)));
    }

    private static String tidy(String world) {
        StringBuilder out = new StringBuilder();
        for (String word : world.split("_")) {
            if (!word.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private Settings.Look lookOf(String world) {
        Settings.WorldEntry entry = plugin.settings().worlds.get(world);
        return plugin.settings().look(entry == null ? null : entry.look());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(true), 40L);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(true), 40L);
    }

    /**
     * Compares the station with the world list and group list, and adjusts it if they differ.
     * Runs every 30 seconds and after any world loads or unloads, since Multiverse-Inventories
     * has no event for a group change and a world can also go away with nothing announcing it.
     */
    void sync(boolean retry) {
        if (generating) {
            syncAgain = true;
            return;
        }
        if (retry) {
            gaveUp.clear();
            caveFailed = false;
        }
        loadGroups();
        boolean changed = repointStale();

        // A return gate goes with its world. If the world comes back, its terrain is new, so it gets a new one.
        for (Gate gate : new ArrayList<>(plugin.store().all())) {
            if (!gate.world.equals(plugin.settings().stationWorld) && !known(gate.world) && gate.id.startsWith("return-")) {
                plugin.store().remove(gate.id);
                plugin.store().clearGenerated(gate.world);
                changed = true;
            }
        }
        // Remember each live gate's group for the day its world is deleted.
        for (Gate gate : plugin.store().inWorld(plugin.settings().stationWorld)) {
            String group = worldToGroup.get(gate.destination);
            if (group != null && !group.equals(gate.group)) {
                gate.group = group;
                changed = true;
            }
        }

        Set<String> dead = new TreeSet<>();
        for (Gate gate : plugin.store().all()) if (plugin.isDead(gate)) dead.add(gate.id);
        if (!dead.equals(deadBefore)) {
            for (String id : dead) if (!deadBefore.contains(id)) plugin.getLogger().warning("Gate " + id + " is dead: its world is gone. /gate remove " + id + " clears it.");
            deadBefore.clear();
            deadBefore.addAll(dead);
            changed = true;
        }
        if (changed) {
            plugin.saveStore();
            plugin.afterGateChange();
        }

        checkReturnSky();

        boolean missing = false;
        for (String world : liveWorlds()) {
            if (!plugin.store().generated(world) && !gaveUp.contains(world)) missing = true;
        }
        // Paper-only (no Multiverse-Core): there's no station to build, so don't try every 30 seconds (K367).
        if (!plugin.worlds().managesWorlds()) return;
        if (missing || !lineup().toString().equals(plugin.store().layout()) || (plugin.cave().needsBuild() && !caveFailed)
            || (plugin.wayportReady() != (plugin.store().get(Gate.WAYPORT_ID) != null) && plugin.cave().exists())) {
            generate(false).thenAccept(problems -> problems.forEach(plugin.getLogger()::warning));
        }
    }

    /**
     * Gates that belong to the cave but point at another world (the cave's world was renamed, or replaced by a new one)
     * are put right: a cave gate in some other world is dropped, to be placed again in this one, and a return gate that
     * leads to the old cave now leads to this one. Returns whether anything changed.
     */
    private boolean repointStale() {
        String cave = plugin.settings().stationWorld;
        boolean changed = false;
        for (Gate gate : new ArrayList<>(plugin.store().all())) {
            boolean caveGate = gate.id.startsWith("station-") || gate.isWayport();
            if (caveGate && !gate.world.equals(cave)) {
                plugin.store().remove(gate.id);
                if (!gate.isWayport()) plugin.store().clearGenerated(gate.destination);
                changed = true;
            } else if (gate.id.startsWith("return-") && gate.link != null && gate.link.startsWith("station-") && !gate.destination.equals(cave)) {
                gate.destination = cave;
                changed = true;
            }
        }
        return changed;
    }

    /** Works out platforms from the station gates as they stand now, so moved gates keep their platform. */
    void buildPlatforms() {
        Settings settings = plugin.settings();
        Map<String, List<Gate>> byGroup = new LinkedHashMap<>();
        // Config order first, so numbers don't depend on gates.yml order.
        for (String group : lineup().keySet()) byGroup.putIfAbsent(group, new ArrayList<>());
        for (Gate p : plugin.store().inWorld(settings.stationWorld)) {
            if (p.leavesServer()) continue;
            byGroup.computeIfAbsent(groupOf(p.destination), k -> new ArrayList<>()).add(p);
        }
        byGroup.values().removeIf(List::isEmpty);

        // Deal unused themes to groups that didn't ask for one, in a fixed shuffled order.
        List<String> free = new ArrayList<>(settings.themes.keySet());
        Collections.shuffle(free, GateForge.random(seed()));
        free.removeAll(settings.platformThemes.values());
        if (free.isEmpty()) free.addAll(settings.themes.keySet());

        platforms.clear();
        int number = 1, next = 0;
        for (var entry : byGroup.entrySet()) {
            String group = entry.getKey();
            String theme = settings.platformThemes.get(group);
            if (theme == null) theme = free.get(next++ % free.size());
            List<Gate> gates = entry.getValue();
            gates.sort(Comparator.comparingInt((Gate p) -> p.alongX() ? p.x : p.z));
            String label = settings.platformLabels.getOrDefault(group, group.replace('_', ' '));
            String design = settings.platformDesigns.get(group); // null: the forge decides from the seed
            boolean worldBlocks = !"theme".equals(settings.platformPalettes.get(group));
            platforms.put(group, new Platform(group, number++, label, settings.themes.get(theme), gates, design, worldBlocks, seed() ^ group.hashCode()));
        }
    }

    List<Platform> platforms() {
        return new ArrayList<>(platforms.values());
    }

    /** The platform a gate belongs to, or null for anything that isn't a gate in the station. */
    Platform platformOf(Gate p) {
        if (p.leavesServer() || !p.world.equals(plugin.settings().stationWorld)) return null;
        return platforms.get(groupOf(p.destination));
    }

    /** The middle of the start chamber, which is where the monument and the Trails board stand. */
    int[] center(World station) {
        return new int[] {plugin.cave().originX(), plugin.cave().originZ()};
    }

    /** Worlds with no gate place in their group's chamber. An admin decides: move a gate by hand, or remove one. */
    private final List<String> overflow = new ArrayList<>();

    /** How many gates each group has, for sizing a new chamber. Counted over the whole lineup, dead gates included. */
    private Map<String, Integer> groupSizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (var e : lineup().entrySet()) sizes.put(e.getKey(), e.getValue().size());
        return sizes;
    }

    private static BlockFace faceOf(char facing) {
        return switch (facing) {
            case 'N' -> BlockFace.NORTH;
            case 'S' -> BlockFace.SOUTH;
            case 'E' -> BlockFace.EAST;
            default -> BlockFace.WEST;
        };
    }

    /**
     * One slot per world, in its group's chamber. A world whose gate already stands at one of the chamber's places keeps
     * it, so a gate doesn't move because another world came or went; new worlds take the free places in order.
     */
    private List<Slot> layout(World station) {
        Cave cave = plugin.cave();
        List<Slot> slots = new ArrayList<>();
        overflow.clear();
        if (!cave.exists()) return slots;
        for (var entry : lineup().entrySet()) {
            CaveLayout.Chamber chamber = cave.plan().ofGroup(entry.getKey());
            if (chamber == null) {
                overflow.addAll(entry.getValue());
                continue;
            }
            List<CaveLayout.Socket> free = new ArrayList<>(cave.plan().socketsOf(chamber.id()));
            Map<String, CaveLayout.Socket> chosen = new LinkedHashMap<>();
            for (String world : entry.getValue()) {
                Gate gate = plugin.store().get("station-" + world);
                if (gate == null) continue;
                for (CaveLayout.Socket socket : free) {
                    int[] at = cave.worldSpot(socket);
                    if (gate.x == at[0] && gate.z == at[2]) {
                        chosen.put(world, socket);
                        break;
                    }
                }
                if (chosen.containsKey(world)) free.remove(chosen.get(world));
            }
            for (String world : entry.getValue()) {
                if (chosen.containsKey(world)) continue;
                if (free.isEmpty()) {
                    overflow.add(world);
                    continue;
                }
                chosen.put(world, free.removeFirst());
            }
            for (String world : entry.getValue()) {
                CaveLayout.Socket socket = chosen.get(world);
                if (socket == null) continue;
                int[] at = cave.worldSpot(socket);
                slots.add(new Slot(world, at[0], at[1], at[2], faceOf(socket.facing())));
            }
        }
        return slots;
    }

    /** A short description of the cave, for /gate station. */
    String planName() {
        Cave cave = plugin.cave();
        return cave.exists() ? cave.plan().chambers().size() + " chambers and " + cave.plan().tunnels().size() + " tunnels" : "not planned yet";
    }

    /** The whole cave, and a margin, must fit inside the border. */
    private void fitBorder() {
        Settings s = plugin.settings();
        World station = Bukkit.getWorld(s.stationWorld);
        if (station == null || !plugin.cave().exists()) return;
        int reach = 0;
        for (CaveLayout.Chamber c : plugin.cave().plan().chambers()) reach = Math.max(reach, Math.max(Math.abs(c.x()), Math.abs(c.z())) + c.radius());
        station.getWorldBorder().setSize(Math.max(s.stationBorder, (reach + 32) * 2));
    }

    /** Tells an admin that a gate couldn't be placed, and what they can do about it. */
    void askAdmin(Player player) {
        if (blockedSlots.isEmpty()) return;
        if (!player.hasPermission("waycave.admin") && !player.hasPermission("waycave.admin.relayout")) return;
        player.sendMessage(plugin.settings().message("station-blocked", "gates", String.join(", ", blockedSlots)));
    }

    List<String> blockedSlots() {
        return blockedSlots;
    }

    private static int lengthOf(List<String> line, int spacing) {
        int length = 0;
        for (String entry : line) length += entry.isEmpty() ? spacing / 2 : spacing;
        return length;
    }

    /** The seed, chosen once: from config if it names one, otherwise at random. */
    long seed() {
        if (plugin.store().seed() == 0) {
            long seed = plugin.settings().stationSeed != 0 ? plugin.settings().stationSeed : new Random().nextLong();
            plugin.store().setSeed(seed);
            plugin.saveStore();
        }
        return plugin.store().seed();
    }

    /**
     * Generates whatever is missing and lays the station out for the current worlds and groups.
     * Station gates just move, since no blocks are involved. Runs on the main thread, chunk loads
     * aside. The only chunks it may generate are ones around the station and a world's spawn, the
     * same ones the first visitor would generate. It never changes a block in an existing one.
     */
    CompletableFuture<List<String>> generate(boolean force) {
        Settings s = plugin.settings();
        World station = Bukkit.getWorld(s.stationWorld);
        List<String> problems = new ArrayList<>();
        if (station == null || generating) {
            if (station == null) problems.add("The cave's world isn't loaded: " + s.stationWorld);
            return CompletableFuture.completedFuture(problems);
        }
        generating = true;
        if (force) {
            gaveUp.clear();
            caveFailed = false;
        }
        List<String> missing = new ArrayList<>();
        for (String world : liveWorlds()) if (!plugin.store().generated(world)) missing.add(world);
        CompletableFuture<List<String>> done = new CompletableFuture<>();

        // Plan a chamber for every group, then build whatever the plan has that the world doesn't. A cave that can't be
        // built, or fails its walk-through, gets no gates: nobody is sent to a place they can't stand.
        Map<String, List<String>> byGroup = lineup();
        for (String group : plugin.cave().plan(seed(), groupSizes())) {
            problems.add("No room left inside the border for a chamber for group " + group + ". Raise cave.border, or remove a group.");
            for (String world : byGroup.getOrDefault(group, List.of())) gaveUp.add(world);
        }
        plugin.cave().ensureBuilt(station).whenComplete((caveProblems, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            problems.addAll(caveProblems);
            caveFailed = !caveProblems.isEmpty();
            if (caveFailed) {
                gaveUp.addAll(missing);
                generating = false;
                done.complete(problems);
                return;
            }
            fitBorder();
            List<Slot> slots = layout(station);
            for (String world : overflow) {
                problems.add("No place left in its chamber for " + world + "'s gate.");
                blockedSlots.add("station-" + world);
                gaveUp.add(world);
            }
            Map<Long, int[]> chunks = new LinkedHashMap<>();
            for (Slot slot : slots) {
                for (int dx : new int[] {-8, 8}) {
                    for (int dz : new int[] {-8, 8}) {
                        int cx = (slot.x() + dx) >> 4, cz = (slot.z() + dz) >> 4;
                        chunks.put(((long) cx << 32) ^ (cz & 0xffffffffL), new int[] {cx, cz});
                    }
                }
            }
            hold(station, new ArrayList<>(chunks.values()), () -> {
                placeStationGates(station, slots, missing, problems);
                placeWayport(station, problems);
            }, () -> generateReturns(new ArrayList<>(missing), problems, done));
        }));
        return done;
    }

    /** The cave couldn't be built or failed its check: an admin has to look, so it isn't tried again until a reload. */
    private boolean caveFailed;

    /**
     * The one gate in the start chamber, to another server through Wayport. It exists only while a Wayport with its API
     * is installed and running: without one the gate is removed, and the socket stands empty. A player Wayport allows
     * who stops inside it is handed to Wayport's API, which counts down and sends them.
     */
    private void placeWayport(World station, List<String> problems) {
        Gate existing = plugin.store().get(Gate.WAYPORT_ID);
        if (!plugin.wayportReady() || !plugin.cave().exists()) {
            if (existing != null) {
                plugin.store().remove(existing.id);
            }
            return;
        }
        List<CaveLayout.Socket> sockets = plugin.cave().plan().socketsOf(0);
        if (sockets.isEmpty()) return;
        int[] at = plugin.cave().worldSpot(sockets.getFirst());
        BlockFace facing = faceOf(sockets.getFirst().facing());
        if (existing == null) {
            plugin.store().put(new Gate(Gate.WAYPORT_ID, plugin.settings().stationWorld, at[0], at[1], at[2], facing, Gate.WAYPORT_DESTINATION));
        } else {
            existing.world = plugin.settings().stationWorld;
            existing.x = at[0];
            existing.y = at[1];
            existing.z = at[2];
            existing.facing = facing;
        }
    }

    /**
     * Loads chunks and keeps them loaded while the work runs on the main thread. Without a ticket a
     * chunk nobody is near can unload again before the next tick, and reading it would load it again.
     */
    private void hold(World world, List<int[]> chunks, Runnable work, Runnable then) {
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int[] c : chunks) loads.add(world.getChunkAtAsync(c[0], c[1], true));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, error) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<Chunk> held = new ArrayList<>();
                for (CompletableFuture<Chunk> load : loads) {
                    Chunk chunk = load.getNow(null);
                    if (chunk != null && chunk.addPluginChunkTicket(plugin)) held.add(chunk);
                }
                try {
                    work.run();
                } finally {
                    held.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                }
                then.run();
            }));
    }

    private void placeStationGates(World station, List<Slot> slots, List<String> missing, List<String> problems) {
        Settings s = plugin.settings();
        blockedSlots.clear();
        // Every gate in the lineup is being placed; only strays (hand-made gates) are obstacles.
        Set<String> placing = new LinkedHashSet<>();
        for (Slot slot : slots) placing.add("station-" + slot.world());
        List<Gate> obstacles = new ArrayList<>();
        for (Gate p : plugin.store().inWorld(s.stationWorld)) if (!placing.contains(p.id)) obstacles.add(p);

        for (Slot slot : slots) {
            String id = "station-" + slot.world();
            if (!placing.contains(id)) continue;
            Settings.Look look = lookOf(slot.world());
            int wanted = slot.y() != null ? slot.y() : s.stationY;
            SafeSpots.Spot spot = plugin.safeSpots().nearLevel(station, slot.x(), wanted, slot.z(), slot.facing(), look, obstacles);
            Gate gate = plugin.store().get(id);
            if (spot == null) {
                problems.add("No safe floor for " + id + " near " + slot.x() + " " + wanted + " " + slot.z());
                blockedSlots.add(id);
                gaveUp.add(slot.world());
                if (gate != null) obstacles.add(gate);
                continue;
            }
            if (gate == null) {
                gate = new Gate(id, s.stationWorld, spot.x(), spot.y(), spot.z(), slot.facing(), slot.world());
                gate.link = "return-" + slot.world();
                gate.group = groupOf(slot.world());
                plugin.store().put(gate);
            } else {
                gate.x = spot.x();
                gate.y = spot.y();
                gate.z = spot.z();
                gate.facing = slot.facing();
            }
            obstacles.add(gate);
        }
    }

    private void generateReturns(List<String> queue, List<String> problems, CompletableFuture<List<String>> done) {
        if (queue.isEmpty()) {
            plugin.store().setLayout(lineup().toString());
            plugin.saveStore();
            plugin.afterGateChange();
            generating = false;
            fitBorder();
            done.complete(problems);
            if (!blockedSlots.isEmpty()) Bukkit.getOnlinePlayers().forEach(this::askAdmin);
            if (syncAgain) {
                syncAgain = false;
                sync(false);
            }
            return;
        }
        String name = queue.removeFirst();
        Runnable next = () -> generateReturns(queue, problems, done);
        Settings s = plugin.settings();
        World world = Bukkit.getWorld(name);
        if (world == null) {
            problems.add("World isn't loaded, so it has no pair yet: " + name);
            gaveUp.add(name);
            next.run();
            return;
        }
        if (plugin.store().get("return-" + name) != null || s.hasOwnWayIn(name)) {
            finishWorld(name, problems);
            next.run();
            return;
        }
        // The lobby's return gate stands by the lobby, not by the world's spawn.
        Location spawn = plugin.arrival().anchorFor(world);
        int scx = spawn.getBlockX() >> 4, scz = spawn.getBlockZ() >> 4;
        List<int[]> around = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) around.add(new int[] {scx + dx, scz + dz});
        hold(world, around, () -> {
            placeReturn(world, spawn, problems);
            finishWorld(name, problems);
        }, next);
    }

    /** One try at finding a return gate's place: how far to walk, whether swimming counts, and the longest drop. */
    private record Pass(int radius, boolean swim, int maxDrop) {}
    // First on foot, then allowing a swim, then down a longer drop for spawns on peaks. The first pass that finds a spot wins.
    private static final List<Pass> PASSES = List.of(new Pass(64, false, 3), new Pass(64, true, 3), new Pass(64, true, 12));

    /**
     * The nearest good spot a person can actually walk to from spawn, with the gate facing back towards spawn. "Good"
     * means firm ground on both sides, so no gate hangs off a cliff, and "walk to" is tested on the real terrain, so no
     * gate ends up behind a wall or across water. In a world with a sky the gate and the ground in front of it are on the
     * surface, or there's no gate and an admin is told: see {@link SafeSpots#covers}.
     */
    private void placeReturn(World world, Location spawn, List<String> problems) {
        Settings s = plugin.settings();
        String name = world.getName();
        SafeSpots spots = plugin.safeSpots();
        boolean needsSky = needsSky(world, spawn);
        int[] walked = new int[PASSES.size()];
        boolean stoodAtSpawn = false;
        for (int i = 0; i < PASSES.size(); i++) {
            Pass pass = PASSES.get(i);
            Map<Long, Integer> walkable;
            if (needsSky) {
                Integer y = SafeSpots.landing(world, spawn, pass.swim());
                if (y == null || SafeSpots.coverAbove(world, spawn.getBlockX(), y, spawn.getBlockZ()) != null) continue;
                stoodAtSpawn = true;
                walkable = spots.walk(world, spawn, spawn.getBlockX(), y, spawn.getBlockZ(), pass.radius(), pass.swim(), pass.maxDrop(), true);
            } else {
                walkable = spots.walkableFrom(world, spawn, pass.radius(), pass.swim(), pass.maxDrop());
            }
            walked[i] = walkable.size();
            if (placeAmong(world, spawn, walkable, pass.radius(), needsSky)) return;
        }
        // A spawn that's buried, in powder snow or under a roof: start from the surface near it instead.
        if (needsSky && !stoodAtSpawn) {
            for (int i = 0; i < PASSES.size(); i++) {
                Pass pass = PASSES.get(i);
                for (Map<Long, Integer> walkable : spots.surfaceWalks(world, spawn, pass.radius(), pass.swim(), pass.maxDrop())) {
                    walked[i] = Math.max(walked[i], walkable.size());
                    if (placeAmong(world, spawn, walkable, pass.radius(), true)) return;
                }
            }
        }
        problems.add("No good spot for a " + (plugin.arrival().isLobbyWorld(name) ? "lobby gate near the lobby" : "return gate near spawn") + " in " + name + " (spawn " + spawn.getBlockX() + " " + spawn.getBlockY() + " " + spawn.getBlockZ()
            + ", reachable columns " + walked[0] + "/" + walked[PASSES.size() - 1] + ")."
            + (needsSky ? " Waycave only puts it on the surface, under open sky or trees." : "")
            + " Place one by hand: /gate create return-" + name + " " + s.stationWorld);
        gaveUp.add(name);
    }

    /** Puts the return gate at the nearest good spot whose front is in this walk. Returns whether it found one. */
    private boolean placeAmong(World world, Location spawn, Map<Long, Integer> walkable, int r, boolean needsSky) {
        Settings s = plugin.settings();
        String name = world.getName();
        Settings.Look look = lookOf(name);
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz >= 36 && dx * dx + dz * dz <= r * r) offsets.add(new int[] {dx, dz});
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        for (int[] o : offsets) {
            BlockFace facing = Math.abs(o[0]) > Math.abs(o[1])
                ? (o[0] > 0 ? BlockFace.WEST : BlockFace.EAST)
                : (o[1] > 0 ? BlockFace.NORTH : BlockFace.SOUTH);
            int x = spawn.getBlockX() + o[0], z = spawn.getBlockZ() + o[1];
            int frontX = x + facing.getModX() * 3, frontZ = z + facing.getModZ() * 3;
            Integer approach = walkable.get(SafeSpots.key(frontX, frontZ));
            if (approach == null) continue;
            if (needsSky && SafeSpots.coverAbove(world, frontX, approach, frontZ) != null) continue; // walked there under cover
            // With a sky, the gate is level with the ground that was walked: 12 up or down could be a cave under it.
            SafeSpots.Spot spot = plugin.safeSpots().nearLevel(world, x, approach, z, facing, look, plugin.store().inWorld(name),
                needsSky ? 1 : 12, needsSky);
            if (spot != null && plugin.safeSpots().firmBothSides(world, spot, facing)) {
                Gate gate = new Gate("return-" + name, name, spot.x(), spot.y(), spot.z(), facing, s.stationWorld);
                gate.label = plugin.arrival().isLobbyWorld(name) ? s.arrival.lobbyLabel() : s.returnLabel;
                gate.link = "station-" + name;
                gate.palette = samplePalette(world, spawn);
                Gate partner = plugin.store().get(gate.link);
                if (partner != null) {
                    partner.palette = gate.palette;
                    if (partner.link == null) partner.link = gate.id; // removing the old return gate cleared it
                }
                plugin.store().put(gate);
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a world's return gate has to be on the surface. Worlds with a ceiling have none. A lobby may be built
     * indoors: when the lobby spot is a floor with a roof over it, its gate is placed by walking from there.
     */
    private boolean needsSky(World world, Location anchor) {
        if (!SafeSpots.hasSky(world)) return false;
        if (!plugin.arrival().isLobbyWorld(world.getName())) return true;
        Integer y = SafeSpots.landing(world, anchor, false);
        return y == null || SafeSpots.coverAbove(world, anchor.getBlockX(), y, anchor.getBlockZ()) == null;
    }

    /** Return gates already looked at since the server started, by id and place. */
    private final Set<String> skyChecked = new java.util.HashSet<>();

    /**
     * Looks for return gates with something over them in worlds with a sky, and says so in the log, once each per server
     * start and place. It never moves or removes one. Only chunks that already exist are read.
     */
    private void checkReturnSky() {
        for (Gate gate : plugin.store().all()) {
            World world = Bukkit.getWorld(gate.world);
            if (!gate.id.startsWith("return-") || world == null || !SafeSpots.hasSky(world)) continue;
            if (!skyChecked.add(gate.id + " " + gate.world + " " + gate.x + " " + gate.y + " " + gate.z)) continue;
            // An indoor lobby's gate belongs indoors. Asked after the line above, so the lobby spot is read once.
            if (plugin.arrival().isLobbyWorld(gate.world) && !needsSky(world, plugin.arrival().anchorFor(world))) continue;
            Settings.Look look = plugin.settings().look(plugin.lookName(gate));
            List<CompletableFuture<Chunk>> loads = new ArrayList<>();
            for (long chunk : SafeSpots.footprintChunks(gate.x, gate.z, look)) {
                loads.add(world.getChunkAtAsync((int) (chunk >> 32), (int) chunk, false));
            }
            CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || loads.stream().anyMatch(load -> load.getNow(null) == null)) return;
                    List<Chunk> held = loads.stream().map(load -> load.getNow(null)).filter(c -> c.addPluginChunkTicket(plugin)).toList();
                    try {
                        org.bukkit.block.Block over = plugin.safeSpots().coverOver(world, gate.x, gate.y, gate.z, gate.facing, look);
                        if (over != null) plugin.getLogger().warning(underCover(gate, over));
                    } finally {
                        held.forEach(c -> c.removePluginChunkTicket(plugin));
                    }
                }));
        }
    }

    /** What an admin is told about a return gate that has something over it. */
    static String underCover(Gate gate, org.bukkit.block.Block over) {
        return gate.id + " at " + gate.world + " " + gate.x + " " + gate.y + " " + gate.z + " is under cover (highest block over it: "
            + over.getType().getKey().getKey() + " at y " + over.getY() + "), so it may be underground. Waycave hasn't moved it. "
            + "If it should be on the surface: /gate remove " + gate.id + ", then /gate addworld " + gate.world + ". "
            + "If it's where you want it, nothing needs doing.";
    }

    /**
     * The blocks that make a world look like itself: the commonest full blocks on the surface around
     * spawn, builds included. Saved with the gates, so they only change when the station is rebuilt.
     */
    private List<String> samplePalette(World world, Location spawn) {
        Map<Material, Integer> counts = new HashMap<>();
        for (int dx = -48; dx <= 48; dx += 2) {
            for (int dz = -48; dz <= 48; dz += 2) {
                int x = spawn.getBlockX() + dx, z = spawn.getBlockZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Material type = world.getHighestBlockAt(x, z).getType();
                if (type.isOccluding() && type != Material.BEDROCK) counts.merge(type, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().sorted(Map.Entry.<Material, Integer>comparingByValue().reversed()).limit(5)
            .map(e -> e.getKey().getKey().getKey()).toList();
    }

    private void finishWorld(String name, List<String> unused) {
        boolean returnNeeded = !plugin.settings().hasOwnWayIn(name);
        if (plugin.store().get("station-" + name) != null && (!returnNeeded || plugin.store().get("return-" + name) != null)) {
            plugin.store().markGenerated(name);
        }
    }
}
