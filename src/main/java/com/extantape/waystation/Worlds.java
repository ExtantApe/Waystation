package com.extantape.waystation;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * What Waystation needs from whoever manages worlds. With Multiverse-Core that's Multiverse, which also makes the
 * station's world. Without it, Paper alone: there's no station and no automatic waygates, but fargates, gates an admin
 * places and the return command all work, so a one-world server can use them.
 */
interface Worlds {
    /** True when Multiverse-Core runs the worlds, so the station and automatic waygates are possible. */
    boolean managesWorlds();

    /** A world that exists, loaded or not. A world that's only unloaded isn't treated as deleted. */
    boolean known(String world);

    /** Overworld-type worlds that could get a gate, by name. */
    List<String> normalWorlds();

    Location spawnOf(World world);

    /** True when you'd play this world in survival, so arriving at its gate matters. */
    boolean isSurvival(World world);

    /** Makes the station's flat world. Only called when {@link #managesWorlds()} is true. */
    void createStationWorld(String name, int y, Runnable done);

    /** Makes the two example worlds, leaving any that already exist alone. */
    void createExampleWorlds(List<String> names);

    /** Moves a player, checking the spot is safe. onFailure gets a reason in words. */
    void teleport(Player player, Location target, Runnable onSuccess, Consumer<String> onFailure);

    static Worlds detect(WaystationPlugin plugin) {
        // Multiverse's classes are only touched when it's really there, since it's optional.
        var mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        return mv != null && mv.isEnabled() ? new MultiverseWorlds(plugin) : new PaperWorlds(plugin);
    }
}
