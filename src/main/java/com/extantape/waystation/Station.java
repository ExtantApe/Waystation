package com.extantape.waystation;

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

/** The station: which world group each world is in, its platforms, and generating linked pairs. */
final class Station implements Listener {
    record Platform(String group, int number, String label, List<BlockData> theme, List<Gate> gates, String design, boolean worldBlocks, long seed) {}
    private record Slot(String world, int x, Integer y, int z, BlockFace facing) {}

    static final String UNGROUPED = "Other";

    private final WaystationPlugin plugin;
    private final Map<String, String> worldToGroup = new LinkedHashMap<>();
    private final Map<String, Platform> platforms = new LinkedHashMap<>();
    private boolean generating, syncAgain;
    /** Worlds we couldn't place. Only a reload or a world event tries them again. */
    private final Set<String> gaveUp = new TreeSet<>();
    private final Set<String> deadBefore = new TreeSet<>();
    /** Station gates with nowhere safe to stand. An admin decides what happens next. */
    private final List<String> blockedSlots = new ArrayList<>();

    Station(WaystationPlugin plugin) {
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
            if (gate.isFargate()) continue; // placed by hand, on no platform
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
        if (retry) gaveUp.clear();
        loadGroups();
        boolean changed = false;

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

        boolean missing = false;
        for (String world : liveWorlds()) {
            if (!plugin.store().generated(world) && !gaveUp.contains(world)) missing = true;
        }
        if (missing || !lineup().toString().equals(plugin.store().layout())) {
            generate(false).thenAccept(problems -> problems.forEach(plugin.getLogger()::warning));
        }
    }

    /** Works out platforms from the station gates as they stand now, so moved gates keep their platform. */
    void buildPlatforms() {
        Settings settings = plugin.settings();
        Map<String, List<Gate>> byGroup = new LinkedHashMap<>();
        // Config order first, so numbers don't depend on gates.yml order.
        for (String group : lineup().keySet()) byGroup.putIfAbsent(group, new ArrayList<>());
        for (Gate p : plugin.store().inWorld(settings.stationWorld)) {
            if (p.isFargate()) continue;
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
        if (p.isFargate() || !p.world.equals(plugin.settings().stationWorld)) return null;
        return platforms.get(groupOf(p.destination));
    }

    int[] center(World station) {
        Settings s = plugin.settings();
        Location spawn = station.getSpawnLocation();
        return new int[] {s.stationCenterX != null ? s.stationCenterX : spawn.getBlockX(), s.stationCenterZ != null ? s.stationCenterZ : spawn.getBlockZ()};
    }

    /** One slot per world: each group's worlds together, platforms dealt to the shorter of two rows. */
    private List<Slot> layout(World station) {
        Settings s = plugin.settings();
        Map<String, List<String>> byGroup = lineup();
        List<Slot> slots = new ArrayList<>();
        if (!s.stationSlots.isEmpty()) {
            List<String> worlds = byGroup.values().stream().flatMap(List::stream).toList();
            for (int i = 0; i < worlds.size() && i < s.stationSlots.size(); i++) {
                Settings.Slot slot = s.stationSlots.get(i);
                slots.add(new Slot(worlds.get(i), slot.x(), slot.y(), slot.z(), slot.facing()));
            }
            return slots;
        }
        return plan(station, byGroup);
    }

    static final String[] PLANS = {"hall", "courtyard", "pinwheel", "cross", "avenues"};

    private record Wall(int x, int z, boolean alongX, BlockFace facing) {}

    /** The name of the floor plan this seed gives. */
    String planName() {
        return PLANS[GateForge.random(seed()).nextInt(PLANS.length)];
    }

    /**
     * This station's floor plan. Platforms are dealt along walls, each facing a walkway, in an order
     * that comes from the seed. Each group keeps its place in that order as others come and go, so a
     * new world only nudges the room rather than reshuffling it.
     */
    private List<Slot> plan(World station, Map<String, List<String>> byGroup) {
        Settings s = plugin.settings();
        Random r = GateForge.random(seed());
        String plan = PLANS[r.nextInt(PLANS.length)];
        int grown = plugin.store().growth();
        int spacing = s.stationSpacing + r.nextInt(3) + grown * 2;
        int aisle = s.stationAisle + 2 * r.nextInt(4) + grown * 4;
        boolean swap = r.nextBoolean(), flipX = r.nextBoolean(), flipZ = r.nextBoolean();

        List<String> groups = new ArrayList<>(byGroup.keySet());
        long seed = seed();
        groups.sort(Comparator.comparingLong(g -> GateForge.random(seed ^ g.hashCode()).nextLong()));

        int wallCount = switch (plan) {
            case "hall" -> 2;
            case "cross" -> 8;
            default -> 4;
        };
        List<List<String>> lines = new ArrayList<>();
        int[] used = new int[wallCount];
        for (int i = 0; i < wallCount; i++) lines.add(new ArrayList<>());
        for (String group : groups) {
            int wall = 0;
            for (int i = 1; i < wallCount; i++) if (used[i] < used[wall]) wall = i;
            if (!lines.get(wall).isEmpty()) {
                lines.get(wall).add("");
                used[wall]++;
            }
            lines.get(wall).addAll(byGroup.get(group));
            used[wall] += byGroup.get(group).size() * 2;
        }
        int longest = 0;
        for (List<String> line : lines) longest = Math.max(longest, lengthOf(line, spacing));
        int half = longest / 2 + spacing, a = aisle / 2;

        List<Wall> walls = switch (plan) {
            case "courtyard" -> List.of(
                new Wall(0, -half, true, BlockFace.SOUTH), new Wall(0, half, true, BlockFace.NORTH),
                new Wall(-half, 0, false, BlockFace.EAST), new Wall(half, 0, false, BlockFace.WEST));
            case "pinwheel" -> List.of(
                new Wall(spacing, -half, true, BlockFace.SOUTH), new Wall(-spacing, half, true, BlockFace.NORTH),
                new Wall(-half, -spacing, false, BlockFace.EAST), new Wall(half, spacing, false, BlockFace.WEST));
            case "cross" -> {
                int reach = a + spacing + longest / 2;
                yield List.of(
                    new Wall(-a, -reach, false, BlockFace.EAST), new Wall(a, -reach, false, BlockFace.WEST),
                    new Wall(-a, reach, false, BlockFace.EAST), new Wall(a, reach, false, BlockFace.WEST),
                    new Wall(-reach, -a, true, BlockFace.SOUTH), new Wall(-reach, a, true, BlockFace.NORTH),
                    new Wall(reach, -a, true, BlockFace.SOUTH), new Wall(reach, a, true, BlockFace.NORTH));
            }
            case "avenues" -> List.of(
                new Wall(0, -a, true, BlockFace.SOUTH), new Wall(0, a, true, BlockFace.NORTH),
                new Wall(0, a + 8, true, BlockFace.SOUTH), new Wall(0, a + 8 + aisle, true, BlockFace.NORTH));
            default -> List.of(new Wall(0, -a, true, BlockFace.SOUTH), new Wall(0, a, true, BlockFace.NORTH));
        };

        int[] center = center(station);
        List<Slot> slots = new ArrayList<>();
        for (int w = 0; w < walls.size(); w++) {
            Wall wall = walls.get(w);
            int along = -lengthOf(lines.get(w), spacing) / 2 + spacing / 2;
            for (String entry : lines.get(w)) {
                if (entry.isEmpty()) {
                    along += spacing / 2;
                    continue;
                }
                int x = wall.alongX() ? wall.x() + along : wall.x(), z = wall.alongX() ? wall.z() : wall.z() + along;
                BlockFace facing = wall.facing();
                if (flipX) { x = -x; if (facing.getModX() != 0) facing = facing.getOppositeFace(); }
                if (flipZ) { z = -z; if (facing.getModZ() != 0) facing = facing.getOppositeFace(); }
                if (swap) {
                    int t = x; x = z; z = t;
                    facing = switch (facing) {
                        case NORTH -> BlockFace.WEST;
                        case SOUTH -> BlockFace.EAST;
                        case EAST -> BlockFace.SOUTH;
                        default -> BlockFace.NORTH;
                    };
                }
                slots.add(new Slot(entry, center[0] + x, null, center[1] + z, facing));
                along += spacing;
            }
        }
        return slots;
    }

    /** An enlarged station must still fit inside its border, with room to walk around the edge. */
    private void fitBorder() {
        Settings s = plugin.settings();
        World station = Bukkit.getWorld(s.stationWorld);
        if (station == null) return;
        int[] center = center(station);
        int reach = 0;
        for (Gate gate : plugin.store().inWorld(s.stationWorld)) {
            reach = Math.max(reach, Math.max(Math.abs(gate.x - center[0]), Math.abs(gate.z - center[1])));
        }
        station.getWorldBorder().setSize(Math.max(s.stationBorder, (reach + 32) * 2));
    }

    /** Tells an admin that a gate couldn't be placed, and what they can do about it. */
    void askAdmin(Player player) {
        if (blockedSlots.isEmpty()) return;
        if (!player.hasPermission("waystation.admin") && !player.hasPermission("waystation.admin.enlarge")) return;
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
            if (station == null) problems.add("The station's world isn't loaded: " + s.stationWorld);
            return CompletableFuture.completedFuture(problems);
        }
        generating = true;
        List<String> missing = new ArrayList<>();
        for (String world : liveWorlds()) if (!plugin.store().generated(world)) missing.add(world);

        List<Slot> slots = layout(station);
        Map<Long, int[]> chunks = new LinkedHashMap<>();
        for (Slot slot : slots) {
            for (int dx : new int[] {-8, 8}) {
                for (int dz : new int[] {-8, 8}) {
                    int cx = (slot.x() + dx) >> 4, cz = (slot.z() + dz) >> 4;
                    chunks.put(((long) cx << 32) ^ (cz & 0xffffffffL), new int[] {cx, cz});
                }
            }
        }
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        hold(station, new ArrayList<>(chunks.values()), () -> placeStationGates(station, slots, missing, problems),
            () -> generateReturns(new ArrayList<>(missing), problems, done));
        return done;
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
        if (plugin.store().get("return-" + name) != null || s.enterCommand(name) != null) {
            finishWorld(name, problems);
            next.run();
            return;
        }
        Location spawn = plugin.spawnOf(world);
        int scx = spawn.getBlockX() >> 4, scz = spawn.getBlockZ() >> 4;
        List<int[]> around = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) around.add(new int[] {scx + dx, scz + dz});
        hold(world, around, () -> {
            placeReturn(world, spawn, problems);
            finishWorld(name, problems);
        }, next);
    }

    /**
     * The nearest good spot a person can actually walk to from spawn, with the gate facing back
     * towards spawn. "Good" means firm ground on both sides, so no gate hangs off a cliff, and "walk
     * to" is tested on the real terrain, so no gate ends up behind a wall or across water.
     */
    private void placeReturn(World world, Location spawn, List<String> problems) {
        Settings s = plugin.settings();
        String name = world.getName();
        Settings.Look look = lookOf(name);
        // First on foot, then allowing a swim, then down a longer drop for spawns on peaks. The first pass that finds a spot wins.
        record Pass(int radius, boolean swim, int maxDrop) {}
        int[] walked = new int[3];
        int pass = 0;
        for (Pass attempt : List.of(new Pass(64, false, 3), new Pass(64, true, 3), new Pass(64, true, 12))) {
            Map<Long, Integer> walkable = plugin.safeSpots().walkableFrom(world, spawn, attempt.radius(), attempt.swim(), attempt.maxDrop());
            walked[pass++] = walkable.size();
            List<int[]> offsets = new ArrayList<>();
            int r = attempt.radius();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz >= 36 && dx * dx + dz * dz <= r * r) offsets.add(new int[] {dx, dz});
            }
            offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
            for (int[] o : offsets) {
                BlockFace facing = Math.abs(o[0]) > Math.abs(o[1])
                    ? (o[0] > 0 ? BlockFace.WEST : BlockFace.EAST)
                    : (o[1] > 0 ? BlockFace.NORTH : BlockFace.SOUTH);
                int x = spawn.getBlockX() + o[0], z = spawn.getBlockZ() + o[1];
                Integer approach = walkable.get(SafeSpots.key(x + facing.getModX() * 3, z + facing.getModZ() * 3));
                if (approach == null) continue;
                SafeSpots.Spot spot = plugin.safeSpots().nearLevel(world, x, approach, z, facing, look, plugin.store().inWorld(name));
                if (spot != null && plugin.safeSpots().firmBothSides(world, spot, facing)) {
                    Gate gate = new Gate("return-" + name, name, spot.x(), spot.y(), spot.z(), facing, s.stationWorld);
                    gate.label = s.returnLabel;
                    gate.link = "station-" + name;
                    gate.palette = samplePalette(world, spawn);
                    Gate partner = plugin.store().get(gate.link);
                    if (partner != null) partner.palette = gate.palette;
                    plugin.store().put(gate);
                    return;
                }
            }
        }
        problems.add("No good spot for a return gate near spawn in " + name + " (spawn " + spawn.getBlockX() + " " + spawn.getBlockY() + " " + spawn.getBlockZ()
            + ", reachable columns " + walked[0] + "/" + walked[2] + "). Place one by hand: /gate create return-" + name + " " + s.stationWorld);
        gaveUp.add(name);
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
        boolean returnNeeded = plugin.settings().enterCommand(name) == null;
        if (plugin.store().get("station-" + name) != null && (!returnNeeded || plugin.store().get("return-" + name) != null)) {
            plugin.store().markGenerated(name);
        }
    }
}
