package com.extantape.waystation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** gates.yml: every gate, plus which worlds have already had their pair generated. */
final class GateStore {
    private final File file;
    private final Map<String, Gate> gates = new LinkedHashMap<>();
    private final Set<String> generatedWorlds = new LinkedHashSet<>();
    private String layout = "";
    private long seed;
    private int growth;

    GateStore(File file) {
        this.file = file;
    }

    /** Reads the file. On a bad file this throws and leaves what's in memory, and the file, untouched. */
    void load() throws IOException, InvalidConfigurationException {
        Map<String, Gate> read = new LinkedHashMap<>();
        Set<String> generated = new LinkedHashSet<>();
        if (file.exists()) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(file);
            generated.addAll(y.getStringList("generated-worlds"));
            layout = y.getString("layout", "");
            seed = y.getLong("seed", 0);
            growth = y.getInt("growth", 0);
            ConfigurationSection all = y.getConfigurationSection("gates");
            if (all != null) {
                for (String id : all.getKeys(false)) {
                    ConfigurationSection s = all.getConfigurationSection(id);
                    if (s == null) throw new InvalidConfigurationException("gates." + id + " is not a section");
                    String world = require(s, "world"), destination = require(s, "destination");
                    Gate p = new Gate(id, world, s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        Settings.facing(s.getString("facing", "north")), destination);
                    p.label = s.getString("label");
                    p.look = s.getString("look");
                    p.enabled = s.getBoolean("enabled", true);
                    p.link = s.getString("link");
                    p.restUntil = s.getLong("rest-until", 0);
                    p.group = s.getString("group");
                    p.palette = s.getStringList("palette");
                    read.put(id, p);
                }
            }
        }
        gates.clear();
        gates.putAll(read);
        generatedWorlds.clear();
        generatedWorlds.addAll(generated);
    }

    /** Writes to a temporary file first, so a crash mid-save can't leave a half-written gates.yml. */
    void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("seed", seed);
        y.set("growth", growth);
        y.set("layout", layout);
        y.set("generated-worlds", new ArrayList<>(generatedWorlds));
        for (Gate p : gates.values()) {
            String at = "gates." + p.id + ".";
            y.set(at + "world", p.world);
            y.set(at + "x", p.x);
            y.set(at + "y", p.y);
            y.set(at + "z", p.z);
            y.set(at + "facing", p.facing.name().toLowerCase());
            y.set(at + "destination", p.destination);
            y.set(at + "label", p.label);
            y.set(at + "look", p.look);
            y.set(at + "enabled", p.enabled);
            y.set(at + "link", p.link);
            if (p.restUntil != 0) y.set(at + "rest-until", p.restUntil);
            y.set(at + "group", p.group);
            if (!p.palette.isEmpty()) y.set(at + "palette", p.palette);
        }
        writeAtomically(file, y.saveToString());
    }

    static void writeAtomically(File target, String text) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.writeString(temp.toPath(), text, StandardCharsets.UTF_8);
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    Gate get(String id) {
        return gates.get(id);
    }

    Collection<Gate> all() {
        return Collections.unmodifiableCollection(gates.values());
    }

    List<Gate> inWorld(String world) {
        List<Gate> found = new ArrayList<>();
        for (Gate p : gates.values()) if (p.world.equals(world)) found.add(p);
        return found;
    }

    void put(Gate p) {
        gates.put(p.id, p);
    }

    void remove(String id) {
        gates.remove(id);
    }

    /** The world groups and worlds the station was last laid out for. A change means laying it out again. */
    /** This station's seed: its floor plan, its gates, its themes. 0 means not chosen yet. */
    long seed() {
        return seed;
    }

    /** How many times an admin has enlarged this station. */
    int growth() {
        return growth;
    }

    void setGrowth(int growth) {
        this.growth = growth;
    }

    void setSeed(long seed) {
        this.seed = seed;
    }

    String layout() {
        return layout;
    }

    void setLayout(String layout) {
        this.layout = layout;
    }

    boolean generated(String world) {
        return generatedWorlds.contains(world);
    }

    void markGenerated(String world) {
        generatedWorlds.add(world);
    }

    void clearGenerated(String world) {
        generatedWorlds.remove(world);
    }

    private static String require(ConfigurationSection s, String key) throws InvalidConfigurationException {
        String value = s.getString(key);
        if (value == null) throw new InvalidConfigurationException(s.getCurrentPath() + "." + key + " is missing");
        return value;
    }
}
