package com.extantape.waystation;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Worlds with Paper alone, when Multiverse-Core isn't installed. No station: it would need a world made for it. */
final class PaperWorlds implements Worlds {
    private final WaystationPlugin plugin;

    PaperWorlds(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean managesWorlds() {
        return false;
    }

    @Override
    public boolean known(String world) {
        return Bukkit.getWorld(world) != null;
    }

    @Override
    public List<String> normalWorlds() {
        return Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).map(World::getName).toList();
    }

    @Override
    public Location spawnOf(World world) {
        return world.getSpawnLocation();
    }

    @Override
    public boolean isSurvival(World world) {
        return Bukkit.getDefaultGameMode() == GameMode.SURVIVAL;
    }

    @Override
    public void createStationWorld(String name, int y, Runnable done) {
        throw new IllegalStateException("The station needs Multiverse-Core");
    }

    @Override
    public void createExampleWorlds(List<String> names) {
        plugin.getLogger().warning("example-worlds needs Multiverse-Core to make worlds, so none were made.");
    }

    @Override
    public void teleport(Player player, Location target, Runnable onSuccess, Consumer<String> onFailure) {
        if (!SafeSpots.safeToStand(target)) {
            onFailure.accept("the spot isn't safe to stand on");
            return;
        }
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(moved -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (moved) onSuccess.run();
            else onFailure.accept("another plugin stopped the teleport");
        }));
    }
}
