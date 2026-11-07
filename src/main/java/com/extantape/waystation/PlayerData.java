package com.extantape.waystation;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** players.yml: each player's last spot per world. */
final class PlayerData {
    record Spot(double x, double y, double z, float yaw, float pitch) {}

    private final File file;
    private final Logger log;
    private final Map<UUID, Map<String, Spot>> spots = new HashMap<>();
    private final Map<UUID, String> fargateReturn = new HashMap<>(); // world;x;y;z;yaw;pitch
    private boolean writable = true;

    PlayerData(File file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // Never overwrite a file we couldn't read: it may be hand-fixable.
            writable = false;
            log.log(Level.SEVERE, file.getName() + " can't be read, so last spots won't be saved until it's fixed", e);
            return;
        }
        for (String id : y.getKeys(false)) {
            ConfigurationSection player = y.getConfigurationSection(id);
            if (player == null) continue;
            UUID uuid = UUID.fromString(id);
            if (player.contains("fargate-return")) fargateReturn.put(uuid, player.getString("fargate-return"));
            ConfigurationSection worlds = player.getConfigurationSection("spots");
            if (worlds == null) continue;
            for (String world : worlds.getKeys(false)) {
                ConfigurationSection s = worlds.getConfigurationSection(world);
                if (s != null) {
                    spots.computeIfAbsent(uuid, k -> new HashMap<>()).put(world, new Spot(s.getDouble("x"), s.getDouble("y"),
                        s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch")));
                }
            }
        }
    }

    private void save() {
        if (!writable) return;
        YamlConfiguration y = new YamlConfiguration();
        fargateReturn.forEach((uuid, spot) -> y.set(uuid + ".fargate-return", spot));
        spots.forEach((uuid, worlds) -> worlds.forEach((world, s) -> {
            String at = uuid + ".spots." + world + ".";
            y.set(at + "x", s.x());
            y.set(at + "y", s.y());
            y.set(at + "z", s.z());
            y.set(at + "yaw", (double) s.yaw());
            y.set(at + "pitch", (double) s.pitch());
        }));
        try {
            GateStore.writeAtomically(file, y.saveToString());
        } catch (IOException e) {
            log.log(Level.SEVERE, "Couldn't save " + file.getName(), e);
        }
    }

    void remember(UUID player, Location at) {
        spots.computeIfAbsent(player, k -> new HashMap<>()).put(at.getWorld().getName(),
            new Spot(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
        save();
    }

    void forget(UUID player, String world) {
        Map<String, Spot> worlds = spots.get(player);
        if (worlds != null && worlds.remove(world) != null) save();
    }

    Location lastSpot(UUID player, World world) {
        Map<String, Spot> worlds = spots.get(player);
        Spot s = worlds == null ? null : worlds.get(world.getName());
        return s == null ? null : new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    }

    /** Where to put a player who left through a fargate when they come back. Survives a restart. */
    void setFargateReturn(UUID player, Location at) {
        fargateReturn.put(player, String.join(";", at.getWorld().getName(), String.valueOf(at.getX()), String.valueOf(at.getY()),
            String.valueOf(at.getZ()), String.valueOf(at.getYaw()), String.valueOf(at.getPitch())));
        save();
    }

    /** The return spot, once. Null if there's none, or its world has gone. */
    Location takeFargateReturn(UUID player) {
        String spot = fargateReturn.remove(player);
        if (spot == null) return null;
        save();
        String[] p = spot.split(";");
        World world = org.bukkit.Bukkit.getWorld(p[0]);
        return world == null ? null : new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
            Float.parseFloat(p[4]), Float.parseFloat(p[5]));
    }

}
