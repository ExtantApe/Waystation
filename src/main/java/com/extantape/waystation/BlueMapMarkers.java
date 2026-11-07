package com.extantape.waystation;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Shows gates as BlueMap markers. BlueMap can restart on its own (/bluemap reload) and drops all
 * markers when it does, so they're put back from its enable callback as well as on every change.
 */
final class BlueMapMarkers {
    private static final String SET_ID = "waystation-gates";

    private final WaystationPlugin plugin;
    private final Consumer<BlueMapAPI> onEnable;
    private final Consumer<BlueMapAPI> onDisable;
    private volatile BlueMapAPI api;

    BlueMapMarkers(WaystationPlugin plugin) {
        this.plugin = plugin;
        onEnable = enabled -> {
            api = enabled;
            Bukkit.getScheduler().runTask(plugin, this::update);
        };
        onDisable = disabled -> api = null;
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    void close() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
    }

    /** Main thread. Rebuilds the marker set of every world. */
    void update() {
        BlueMapAPI bluemap = api;
        if (bluemap == null) return;
        for (World world : Bukkit.getWorlds()) {
            MarkerSet set = MarkerSet.builder().label(plugin.settings().bluemapSetLabel).toggleable(true).defaultHidden(false).build();
            for (Gate gate : plugin.store().inWorld(world.getName())) {
                if (plugin.settings().hiddenMarkerGroups.contains(plugin.station().groupOf(gate.isFargate() ? gate.world : gate.destination))
                    || plugin.settings().hiddenMarkerGroups.contains(plugin.station().groupOf(gate.world))) {
                    continue;
                }
                String name = plugin.isDead(gate)
                    ? plugin.station().plainName(gate.destination) + " (line closed)"
                    : plugin.plainLabelOf(gate);
                String label = world.getName().equals(plugin.settings().stationWorld) ? name : gate.isFargate() ? name.replace('\n', ' ') : "Waygate to " + name;
                set.getMarkers().put(gate.id, POIMarker.builder()
                    .label(label)
                    .position((double) gate.x + 0.5, gate.y + 1.0, gate.z + 0.5)
                    .maxDistance(10000)
                    .build());
            }
            bluemap.getWorld(world).ifPresent(mapped -> mapped.getMaps().forEach(map -> {
                if (set.getMarkers().isEmpty()) map.getMarkerSets().remove(SET_ID);
                else map.getMarkerSets().put(SET_ID, set);
            }));
        }
    }
}
