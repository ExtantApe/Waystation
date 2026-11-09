package com.extantape.waystation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** An immutable reading of config.yml. A failed read throws, so /gate reload can keep the old one. */
final class Settings {
    record Look(int width, int height, BlockData frame, BlockData fill, float labelScale) {}
    record WorldEntry(String name, boolean enabled, String label, String look, Cost price, String arrival, String enterCommand, boolean handOver) {}
    record Slot(int x, Integer y, int z, BlockFace facing) {}
    /** Another server a fargate leads to, and the client versions it can take. */
    record FargateTarget(String name, String label, String host, int port, Set<Integer> protocols, String permission) {}
    /**
     * The return command: off unless it names a fargate destination. checkHost and checkPort are where this server pings
     * to see the destination is up, which can differ from the address players connect to.
     */
    record Return(boolean enabled, String destination, String command, List<String> aliases, String permission, int countdownSeconds,
                  boolean sounds, String checkHost, int checkPort, int checkTimeoutMs) {
        /** Null when it can be used, otherwise why not, in words an admin can act on. */
        String problem(Map<String, FargateTarget> fargates) {
            if (!enabled) return "return.enabled is false";
            if (destination.isEmpty()) return "return.destination is empty";
            if (!fargates.containsKey(destination)) return "return.destination \"" + destination + "\" isn't under fargate-destinations";
            return null;
        }
    }
    /** A price: any mix of XP levels, items and money. Money needs Vault and an economy plugin. */
    record Cost(int xpLevels, Map<Material, Integer> items, double money) {
        static Cost read(ConfigurationSection c, String path) {
            if (c == null) return new Cost(0, Map.of(), 0);
            Map<Material, Integer> items = new LinkedHashMap<>();
            ConfigurationSection list = c.getConfigurationSection("items");
            if (list != null) {
                for (String key : list.getKeys(false)) {
                    Material material = Material.matchMaterial(key);
                    if (material == null || !material.isItem()) throw new IllegalArgumentException(path + ".items: unknown item " + key);
                    items.put(material, positive(list.getInt(key), path + ".items." + key));
                }
            }
            return new Cost(Math.max(0, c.getInt("xp-levels", 0)), items, Math.max(0, c.getDouble("money", 0)));
        }
        boolean free() { return xpLevels == 0 && items.isEmpty() && money == 0; }
    }

    final Map<String, Look> looks = new LinkedHashMap<>();
    final Map<String, WorldEntry> worlds = new LinkedHashMap<>();

    final String stationWorld, returnLabel, stationLabel, deadLabel;
    final BlockData deadFrame;
    final boolean autoWorlds, protectionEnabled, bluemapEnabled, exampleWorlds;
    final String bluemapSetLabel;
    final List<String> excludeWorlds, guardedCommands;
    final int tileDepth;
    final Map<String, List<BlockData>> themes = new LinkedHashMap<>();
    final Map<String, String> platformLabels = new LinkedHashMap<>(), platformThemes = new LinkedHashMap<>();
    final Integer stationCenterX, stationCenterZ;
    final int stationY, stationSpacing, stationAisle;
    final List<Slot> stationSlots = new ArrayList<>();

    final int travelCooldownTicks;

    final boolean moveEnabled;
    final String movePermission;
    final long moveRestSeconds;
    final Set<String> moveWorlds;
    final Map<String, FargateTarget> fargates = new LinkedHashMap<>();
    final String fargateLabel;
    final Map<String, String> platformDesigns = new LinkedHashMap<>(), platformPalettes = new LinkedHashMap<>();
    final int tileSize;
    final long stationSeed;
    final boolean standardWorldGates, islandPosts;
    final String islandPostLabel;
    /** How dark the plate behind floating labels is, in percent. 0 keeps Minecraft's own. */
    final int labelBacking;
    final int moveHearts, gentleHearts;
    final Set<String> gentleWorlds, hiddenMarkerGroups;
    final int moveSearchChunks;
    final List<String> buyCommands;
    final boolean stationAutoCreate;
    final int stationBorder;

    final boolean escapeEnabled;
    final String escapeCommand, escapePermission;
    final List<String> escapeAliases;
    final Return returnCommand;
    final long escapeCooldownSeconds;

    /** The arrival flow: the lobby gate into the station, and the welcome inside it. */
    record Arrival(boolean lobbyGate, String lobbyWorld, double[] lobbySpot, String lobbyLabel,
                   boolean welcome, int welcomeAfterDays, List<String> welcomeText) {}
    final Arrival arrival;

    /** The Departures Board beside the station's centrepiece. */
    record Board(boolean enabled, String title, String empty, double distance) {}
    final Board board;

    private final Map<String, String> messages = new LinkedHashMap<>();

    Settings(FileConfiguration c) {
        ConfigurationSection looksSection = c.getConfigurationSection("looks");
        if (looksSection == null) throw new IllegalArgumentException("looks: section is missing");
        for (String name : looksSection.getKeys(false)) {
            ConfigurationSection s = looksSection.getConfigurationSection(name);
            if (s == null) throw new IllegalArgumentException("looks." + name + " is not a section");
            looks.put(name, new Look(
                positive(s.getInt("width", 3), "looks." + name + ".width"),
                positive(s.getInt("height", 4), "looks." + name + ".height"),
                Bukkit.createBlockData(s.getString("frame", "crying_obsidian")),
                Bukkit.createBlockData(s.getString("fill", "light_blue_stained_glass")),
                (float) s.getDouble("label-scale", 1.4)));
        }
        if (!looks.containsKey("default")) throw new IllegalArgumentException("looks.default is required");

        ConfigurationSection worldsSection = c.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String name : worldsSection.getKeys(false)) {
                ConfigurationSection s = worldsSection.getConfigurationSection(name);
                if (s == null) throw new IllegalArgumentException("worlds." + name + " is not a section");
                String look = s.getString("look");
                if (look != null && !looks.containsKey(look)) {
                    throw new IllegalArgumentException("worlds." + name + " names a look that doesn't exist: " + look);
                }
                boolean handOver = s.getBoolean("hand-over", false);
                if (handOver && s.getString("enter-command") != null) {
                    throw new IllegalArgumentException("worlds." + name + " has both hand-over and enter-command; pick one");
                }
                worlds.put(name, new WorldEntry(name, s.getBoolean("enabled", true), s.getString("label", name), look,
                    Cost.read(s.getConfigurationSection("price"), "worlds." + name + ".price"), s.getString("arrival"), s.getString("enter-command"), handOver));
            }
        }

        arrival = readArrival(c);
        board = readBoard(c);
        stationWorld = c.getString("station.world", "waystation");
        returnLabel = c.getString("station.return-label", "<gold>Station");
        stationLabel = c.getString("station.station-label", "");
        deadLabel = c.getString("station.dead-label", "<white><name></white> <gray>(line closed)");
        deadFrame = Bukkit.createBlockData(c.getString("station.dead-frame", "cracked_deepslate_bricks"));
        bluemapEnabled = c.getBoolean("bluemap.enabled", true);
        bluemapSetLabel = c.getString("bluemap.set-label", "Gates");
        autoWorlds = c.getBoolean("auto-worlds", true);
        exampleWorlds = c.getBoolean("example-worlds", false);
        excludeWorlds = c.getStringList("exclude-worlds");
        protectionEnabled = c.getBoolean("protection.enabled", true);
        guardedCommands = c.getStringList("protection.guarded-commands");
        tileDepth = Math.max(0, c.getInt("station.tile-depth", 5));
        ConfigurationSection themeSection = c.getConfigurationSection("station.themes");
        if (themeSection != null) {
            for (String name : themeSection.getKeys(false)) {
                List<BlockData> blocks = new ArrayList<>();
                for (String block : themeSection.getStringList(name)) blocks.add(Bukkit.createBlockData(block));
                if (blocks.isEmpty()) throw new IllegalArgumentException("station.themes." + name + " has no blocks");
                themes.put(name, blocks);
            }
        }
        if (themes.isEmpty()) throw new IllegalArgumentException("station.themes needs at least one theme");
        ConfigurationSection platforms = c.getConfigurationSection("station.platforms");
        if (platforms != null) {
            for (String group : platforms.getKeys(false)) {
                String label = platforms.getString(group + ".label"), theme = platforms.getString(group + ".theme");
                if (label != null) platformLabels.put(group, label);
                String design = platforms.getString(group + ".design"), palette = platforms.getString(group + ".frame-blocks");
                if (design != null) platformDesigns.put(group, design);
                if (palette != null) platformPalettes.put(group, palette);
                if (theme != null) {
                    if (!themes.containsKey(theme)) throw new IllegalArgumentException("station.platforms." + group + " names a theme that doesn't exist: " + theme);
                    platformThemes.put(group, theme);
                }
            }
        }
        stationCenterX = c.isInt("station.center.x") ? c.getInt("station.center.x") : null;
        stationCenterZ = c.isInt("station.center.z") ? c.getInt("station.center.z") : null;
        stationY = c.getInt("station.y", -60);
        stationSpacing = positive(c.getInt("station.spacing", 7), "station.spacing");
        stationAisle = positive(c.getInt("station.aisle", 14), "station.aisle");
        for (Map<?, ?> m : c.getMapList("station.slots")) {
            Object y = m.get("y");
            stationSlots.add(new Slot(number(m, "x"), y instanceof Number n ? n.intValue() : null, number(m, "z"),
                facing(String.valueOf(m.containsKey("facing") ? m.get("facing") : "north"))));
        }

        travelCooldownTicks = Math.max(0, c.getInt("travel.cooldown-ticks", 40));

        moveEnabled = c.getBoolean("move.enabled", true);
        movePermission = c.getString("move.permission", "waystation.move");
        moveRestSeconds = Math.max(0, c.getLong("move.rest-seconds", 86400));
        moveWorlds = Set.copyOf(c.getStringList("move.allowed-worlds"));
        fargateLabel = c.getString("fargate-label", "<red><bold>Leaving this server</bold></red>\n<white><name></white>");
        ConfigurationSection targets = c.getConfigurationSection("fargate-destinations");
        if (targets != null) {
            for (String name : targets.getKeys(false)) {
                ConfigurationSection t = targets.getConfigurationSection(name);
                if (t == null || t.getString("host") == null) throw new IllegalArgumentException("fargate-destinations." + name + " needs a host");
                List<Integer> protocols = t.getIntegerList("protocols");
                if (protocols.isEmpty()) throw new IllegalArgumentException("fargate-destinations." + name + " needs protocols, the client versions it accepts");
                fargates.put(name, new FargateTarget(name, t.getString("label", name), t.getString("host"), t.getInt("port", 25565),
                    Set.copyOf(protocols), t.getString("permission", "waystation.fargate." + name)));
            }
        }
        moveHearts = positive(c.getInt("move.hearts", 6), "move.hearts");
        gentleHearts = positive(c.getInt("move.gentle-hearts", 3), "move.gentle-hearts");
        tileSize = Math.max(1, c.getInt("station.tile-size", 2));
        stationSeed = c.getLong("station.seed", 0);
        islandPosts = c.getBoolean("island-posts.enabled", true);
        labelBacking = Math.max(0, Math.min(100, c.getInt("label-backing", Labels.DEFAULT_BACKING)));
        islandPostLabel = c.getString("island-posts.label", "<gold>To the station");
        gentleWorlds = Set.copyOf(c.getStringList("move.gentle-worlds"));
        hiddenMarkerGroups = Set.copyOf(c.getStringList("bluemap.hide-groups"));
        moveSearchChunks = Math.max(0, c.getInt("move.search-chunks", 2));
        standardWorldGates = "standard".equalsIgnoreCase(c.getString("world-gates", "varied"));
        buyCommands = c.getStringList("buy.grant-commands");
        stationAutoCreate = c.getBoolean("station.auto-create", true);
        stationBorder = Math.max(32, c.getInt("station.border", 300));

        escapeEnabled = c.getBoolean("escape.enabled", true);
        escapeCommand = c.getString("escape.command", "station").toLowerCase(Locale.ROOT);
        escapeAliases = c.getStringList("escape.aliases");
        returnCommand = new Return(c.getBoolean("return.enabled", false), c.getString("return.destination", "").trim(),
            commandName(c.getString("return.command", "return"), "return"),
            c.getStringList("return.aliases").stream().map(a -> commandName(a, "")).filter(a -> !a.isEmpty()).distinct().toList(),
            c.getString("return.permission", "waystation.return"), positive(c.getInt("return.countdown-seconds", 5), "return.countdown-seconds"),
            c.getBoolean("return.sounds", true), c.getString("return.check-host", "").trim(), c.getInt("return.check-port", 0),
            Math.max(250, c.getInt("return.check-timeout-ms", 3000)));
        escapePermission = c.getString("escape.permission", "waystation.escape");
        escapeCooldownSeconds = Math.max(0, c.getLong("escape.cooldown-seconds", 0));

        // The jar's own messages first, so a config.yml from an older version still gets the newer ones, then the server's.
        ConfigurationSection shipped = c.getDefaults() == null ? null : c.getDefaults().getConfigurationSection("messages");
        for (ConfigurationSection text : new ConfigurationSection[] {shipped, c.getConfigurationSection("messages")}) {
            if (text == null) continue;
            for (String key : text.getKeys(false)) messages.put(key, text.getString(key, ""));
        }
    }

    /** A world you enter by running a command, like OneBlock's own join. It has no return gate. */
    String enterCommand(String world) {
        WorldEntry entry = worlds.get(world);
        return entry == null ? null : entry.enterCommand();
    }

    /** A world whose own plugin takes the player in, through WaystationHandOverEvent. It has no return gate. */
    boolean handsOver(String world) {
        WorldEntry entry = worlds.get(world);
        return entry != null && entry.handOver();
    }

    /** Another plugin brings players in and out of this world, so Waystation gives it no return gate. */
    boolean hasOwnWayIn(String world) {
        return enterCommand(world) != null || handsOver(world);
    }

    /** The hearts a waygate asks for in this world. */
    int heartsFor(String world) {
        return gentleWorlds.contains(world) ? gentleHearts : moveHearts;
    }

    Look look(String name) {
        return looks.getOrDefault(name == null ? "default" : name, looks.get("default"));
    }

    /** Does a pattern in exclude-worlds, where * matches anything, cover this world? */
    boolean excluded(String world) {
        for (String pattern : excludeWorlds) {
            String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            if (world.toLowerCase(Locale.ROOT).matches(regex.toLowerCase(Locale.ROOT).replace("\\q", "\\Q").replace("\\e", "\\E"))) return true;
        }
        return false;
    }

    /** Is this world open as a destination? A world with no entry (the station, usually) is open. */
    boolean worldOpen(String world) {
        WorldEntry e = worlds.get(world);
        return e == null || e.enabled();
    }

    /** Renders a message. Pairs are placeholder name, value. A blank message renders to null. */
    Component message(String key, String... pairs) {
        return message(key, TagResolver.empty(), pairs);
    }

    /** As above, with extra tags, such as a label that's MiniMessage itself. */
    Component message(String key, TagResolver extra, String... pairs) {
        String template = messages.getOrDefault(key, key);
        if (template.isBlank()) return null;
        TagResolver.Builder tags = TagResolver.builder().resolver(extra);
        for (int i = 0; i + 1 < pairs.length; i += 2) tags.resolver(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        return MiniMessage.miniMessage().deserialize(template, tags.build());
    }

    static BlockFace facing(String name) {
        BlockFace face = BlockFace.valueOf(name.toUpperCase(Locale.ROOT));
        if (!face.isCartesian() || face == BlockFace.UP || face == BlockFace.DOWN) {
            throw new IllegalArgumentException("facing must be north, south, east or west: " + name);
        }
        return face;
    }

    /** A command name as typed in config: lowercase, without a slash. */
    private static String commandName(String raw, String fallback) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) name = name.substring(1);
        return name.isEmpty() ? fallback : name;
    }

    private static int positive(int value, String path) {
        if (value <= 0) throw new IllegalArgumentException(path + " must be above zero");
        return value;
    }

    private static int number(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("station.slots: each slot needs a number for " + key);
    }

    static Arrival readArrival(FileConfiguration c) {
        String world = c.getString("arrival.lobby-world", "").trim();
        double[] spot = null;
        if (c.isConfigurationSection("arrival.lobby-spot")) {
            var s = c.getConfigurationSection("arrival.lobby-spot");
            if (!s.isSet("x") || !s.isSet("y") || !s.isSet("z"))
                throw new IllegalArgumentException("arrival.lobby-spot needs x, y and z, or leave it out to use the world's spawn");
            spot = new double[] {s.getDouble("x"), s.getDouble("y"), s.getDouble("z")};
        }
        int days = c.getInt("arrival.welcome-after-days", 30);
        if (days < 1 || days > 3650) throw new IllegalArgumentException("arrival.welcome-after-days must be from 1 to 3650");
        List<String> text = c.getStringList("arrival.welcome-text");
        if (text.size() > 8) throw new IllegalArgumentException("arrival.welcome-text has more than 8 lines; keep the welcome short");
        return new Arrival(c.getBoolean("arrival.lobby-gate", true), world, spot,
            c.getString("arrival.lobby-label", "<gold><bold>Central Station</bold>"),
            c.getBoolean("arrival.welcome", true), days, List.copyOf(text));
    }

    static Board readBoard(FileConfiguration c) {
        double distance = c.getDouble("departures-board.distance", 5);
        if (distance < 2 || distance > 30) throw new IllegalArgumentException("departures-board.distance must be from 2 to 30 blocks");
        return new Board(c.getBoolean("departures-board.enabled", true),
            c.getString("departures-board.title", "<gold><bold>Departures</bold>"),
            c.getString("departures-board.empty", "No gates are open to you yet."), distance);
    }
}
