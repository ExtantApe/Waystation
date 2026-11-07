package com.extantape.waystation;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * OneBlock gives each player an island on a grid, so no fixed gate can serve them. Instead, any island
 * with someone on it gets a glowing post beside its block, and clicking that takes you to the station.
 *
 * The post is display entities, not blocks. It can't be broken, nothing falls, and it can't be left
 * behind when OneBlock clears an island for reuse. It works from OneBlock's own settings, reading only
 * its config file, so it needs nothing from the plugin's code.
 */
final class IslandMarkers implements Listener {
    static final String TAG = "waystation-island-post";
    private static final int NEAR = 48;
    private static final long CONFIRM_MS = 5000;

    private final WaystationPlugin plugin;
    private final Map<String, List<Entity>> posts = new HashMap<>();
    private final Map<UUID, Long> confirming = new HashMap<>();
    private String worldName;
    private double originX, originZ;
    private int originY, spacing;

    IslandMarkers(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    /** Reads where OneBlock puts its islands. No OneBlock, no posts. */
    void reload() {
        removeAll();
        worldName = null;
        File file = new File(plugin.getDataFolder().getParentFile(), "Oneblock/config.yml");
        if (!plugin.settings().islandPosts || !file.exists()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        spacing = c.getInt("set", 0);
        if (spacing <= 0 || c.getString("world") == null) return;
        worldName = c.getString("world");
        originX = c.getDouble("x");
        originY = c.getInt("y");
        originZ = c.getDouble("z");
    }

    /** Once a second: a post for every island someone is near, and none for the rest. */
    void pass() {
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        Map<String, int[]> wanted = new HashMap<>();
        if (world != null) {
            for (Player player : world.getPlayers()) {
                Location at = player.getLocation();
                int cx = (int) Math.round((at.getX() - originX) / spacing), cz = (int) Math.round((at.getZ() - originZ) / spacing);
                int bx = (int) originX + cx * spacing, bz = (int) originZ + cz * spacing;
                if (Math.abs(at.getX() - bx) <= NEAR && Math.abs(at.getZ() - bz) <= NEAR) wanted.put(cx + "," + cz, new int[] {bx, bz});
            }
        }
        posts.entrySet().removeIf(entry -> {
            if (wanted.containsKey(entry.getKey()) && entry.getValue().stream().allMatch(Entity::isValid)) return false;
            entry.getValue().forEach(Entity::remove);
            return true;
        });
        wanted.forEach((key, block) -> posts.computeIfAbsent(key, k -> spawnPost(world, block[0], block[1])));
    }

    /** Hangs off the side of the block, level with it, with its light at about head height. */
    private List<Entity> spawnPost(World world, int bx, int bz) {
        List<Entity> out = new ArrayList<>();
        Location at = new Location(world, bx + 2.5, originY, bz + 0.5);
        for (int up = 0; up < 2; up++) {
            float lift = up;
            out.add(world.spawn(at, BlockDisplay.class, e -> {
                prepare(e);
                e.setBlock(Material.OAK_FENCE.createBlockData());
                e.setTransformation(new Transformation(new Vector3f(-0.5f, lift, -0.5f), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            }));
        }
        out.add(world.spawn(at, BlockDisplay.class, e -> {
            prepare(e);
            e.setBlock(Material.SEA_LANTERN.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(-0.25f, 2.1f, -0.25f), new AxisAngle4f(), new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f()));
        }));
        out.add(world.spawn(at.clone().add(0, 2.9, 0), TextDisplay.class, e -> {
            prepare(e);
            e.text(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(plugin.settings().islandPostLabel));
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(15, 15));
        }));
        out.add(world.spawn(at, Interaction.class, e -> {
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setInteractionWidth(0.8f);
            e.setInteractionHeight(2.7f);
        }));
        return out;
    }

    private static void prepare(Display e) {
        e.setPersistent(false);
        e.addScoreboardTag(TAG);
        e.setGravity(false);
        e.setInvulnerable(true);
    }

    /** Two clicks, since it stands right where people will be building. */
    @EventHandler
    public void onClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getRightClicked().getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (now - confirming.getOrDefault(player.getUniqueId(), 0L) > CONFIRM_MS) {
            confirming.put(player.getUniqueId(), now);
            var text = plugin.settings().message("island-post-confirm");
            if (text != null) player.sendMessage(text);
            return;
        }
        confirming.remove(player.getUniqueId());
        plugin.travel().toStation(player, worldName);
    }

    void removeAll() {
        posts.values().forEach(list -> list.forEach(Entity::remove));
        posts.clear();
    }
}
