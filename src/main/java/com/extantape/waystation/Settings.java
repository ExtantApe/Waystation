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
    record WorldEntry(String name, boolean enabled, String label, String look, Cost price, String arrival, String enterCommand) {}
    record Slot(int x, Integer y, int z, BlockFace facing) {}
    /** Another server a fargate leads to, and the client versions it can take. */
    record FargateTarget(String name, String label, String host, int port, Set<Integer> protocols, String permission) {}
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
    final int moveHearts, gentleHearts;
    final Set<String> gentleWorlds, hiddenMarkerGroups;
    final int moveSearchChunks;
    final List<String> buyCommands;
    final boolean stationAutoCreate;
    final int stationBorder;

    final boolean escapeEnabled;
    final String escapeCommand, escapePermission;
    final List<String> escapeAliases;
    final long escapeCooldownSeconds;

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
                worlds.put(name, new WorldEntry(name, s.getBoolean("enabled", true), s.getString("label", name), look,
                    Cost.read(s.getConfigurationSection("price"), "worlds." + name + ".price"), s.getString("arrival"), s.getString("enter-command")));
            }
        }

        stationWorld = c.getString("station.world", "waystation");
        returnLabel = c.getString("station.return-label", "<gold>Station");
        stationLabel = c.getString("station.station-label", "");
        deadLabel = c.getString("station.dead-label", "<dark_gray><name> <gray>(line closed)");
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
        escapePermission = c.getString("escape.permission", "waystation.escape");
        escapeCooldownSeconds = Math.max(0, c.getLong("escape.cooldown-seconds", 0));

        ConfigurationSection text = c.getConfigurationSection("messages");
        if (text != null) {
            for (String key : text.getKeys(false)) messages.put(key, text.getString(key, ""));
        }
    }

    /** A world you enter by running a command, like OneBlock's own join. It has no return gate. */
    String enterCommand(String world) {
        WorldEntry entry = worlds.get(world);
        return entry == null ? null : entry.enterCommand();
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
        String template = messages.getOrDefault(key, key);
        if (template.isBlank()) return null;
        TagResolver.Builder tags = TagResolver.builder();
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

    private static int positive(int value, String path) {
        if (value <= 0) throw new IllegalArgumentException(path + " must be above zero");
        return value;
    }

    private static int number(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("station.slots: each slot needs a number for " + key);
    }
}
