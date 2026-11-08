package com.extantape.waystation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;

/** Worlds through Multiverse-Core. The only class that touches Multiverse's API. */
final class MultiverseWorlds implements Worlds {
    private final WaystationPlugin plugin;

    MultiverseWorlds(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    private static WorldManager manager() {
        return MultiverseCoreApi.get().getWorldManager();
    }

    @Override
    public boolean managesWorlds() {
        return true;
    }

    @Override
    public boolean known(String world) {
        return manager().getWorld(world).isDefined();
    }

    @Override
    public List<String> normalWorlds() {
        List<String> names = new ArrayList<>();
        for (MultiverseWorld w : manager().getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) names.add(w.getName());
        return names;
    }

    @Override
    public Location spawnOf(World world) {
        LoadedMultiverseWorld mv = manager().getLoadedWorld(world).getOrNull();
        return mv != null && mv.getSpawnLocation() != null ? mv.getSpawnLocation() : world.getSpawnLocation();
    }

    @Override
    public boolean isSurvival(World world) {
        LoadedMultiverseWorld mv = manager().getLoadedWorld(world).getOrNull();
        return mv != null && mv.getGameMode() == GameMode.SURVIVAL;
    }

    @Override
    public void createStationWorld(String name, int y, Runnable done) {
        manager().createWorld(CreateWorldOptions.worldName(name).environment(World.Environment.NORMAL)
                .worldType(WorldType.FLAT).generateStructures(false))
            .onSuccess(created -> {
                created.setGameMode(GameMode.ADVENTURE);
                created.setDifficulty(Difficulty.PEACEFUL);
                created.setHunger(false);
                created.setPvp(false);
                created.setBedRespawn(false);
                created.setSpawnLocation(new Location(created.getBukkitWorld().getOrNull(), 0.5, y, 0.5));
                plugin.getLogger().info("Created the station's world: " + name);
                done.run();
            })
            .onFailure(failure -> plugin.getLogger().severe("Couldn't create the station's world: " + failure.getFailureMessage()));
    }

    @Override
    public void createExampleWorlds(List<String> names) {
        for (String name : names) {
            if (manager().getWorld(name).isDefined()) continue;
            boolean creative = name.endsWith("creative");
            manager().createWorld(CreateWorldOptions.worldName(name).environment(World.Environment.NORMAL)
                    .worldType(creative ? WorldType.FLAT : WorldType.NORMAL))
                .onSuccess(created -> {
                    if (creative) created.setGameMode(GameMode.CREATIVE);
                    plugin.getLogger().info("Created an example world: " + name);
                })
                .onFailure(failure -> plugin.getLogger().severe("Couldn't create " + name + ": " + failure.getFailureMessage()));
        }
    }

    @Override
    public void teleport(Player player, Location target, Runnable onSuccess, Consumer<String> onFailure) {
        // Through Multiverse's own teleporter, so Multiverse-Inventories swaps inventories as it should.
        // Riders are refused before a trip, so it's only ever the player.
        MultiverseCoreApi.get().getSafetyTeleporter().to(target).by(player).checkSafety(true).teleportSingle(player)
            .onSuccess(onSuccess)
            .onFailureCount(failures -> onFailure.accept(failures.keySet().stream().map(String::valueOf).collect(Collectors.joining(", "))));
    }
}
