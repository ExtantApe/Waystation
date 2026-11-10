package com.extantape.waystation;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * The arrival flow (plan phase 3, owner 2026-11-09): the lobby gate into the station, and a short welcome inside it.
 *
 * <p>The lobby comes from Latchkey when it's installed and has one set up, otherwise from {@code arrival.lobby-world}.
 * Latchkey stays optional: Waystation doesn't build against it, and reads its {@code lobbyLocation()} method and its
 * {@code PlayerArrivedEvent} by name (crossing latchkey-waystation). If they don't match, it falls back to the config.
 */
final class Arrival implements Listener {
    static final String ARRIVED = "com.extantape.latchkey.api.PlayerArrivedEvent";
    /** Furthest a lobby gate may stand from the lobby before it's put back: the placement search radius, and a little. */
    static final int LOBBY_REACH = 72;

    private final WaystationPlugin plugin;
    private final NamespacedKey seen;
    private Method latchkeyLobby;
    private Plugin latchkey;
    private Location lobby;

    Arrival(WaystationPlugin plugin) {
        this.plugin = plugin;
        this.seen = new NamespacedKey(plugin, "station_seen");
    }

    /** Finds Latchkey's lobby by name, if Latchkey is here. Called once, when every plugin has enabled. */
    void hookLatchkey() {
        Plugin lk = Bukkit.getPluginManager().getPlugin("Latchkey");
        if (lk == null || !lk.isEnabled()) return;
        try {
            latchkeyLobby = lk.getClass().getMethod("lobbyLocation");
            latchkey = lk;
            Class<? extends Event> arrived = lk.getClass().getClassLoader().loadClass(ARRIVED).asSubclass(Event.class);
            Method getLobby = arrived.getMethod("getLobby");
            Bukkit.getPluginManager().registerEvent(arrived, this, EventPriority.MONITOR, (l, e) -> {
                if (!arrived.isInstance(e)) return;
                try {
                    Location at = (Location) getLobby.invoke(e);
                    if (!same(at, lobby)) refresh();
                } catch (ReflectiveOperationException ex) {
                    plugin.getLogger().log(Level.WARNING, "Couldn't read Latchkey's arrival event", ex);
                }
            }, plugin);
            plugin.getLogger().info("Using Latchkey's lobby for the lobby gate.");
        } catch (ReflectiveOperationException | ClassCastException e) {
            latchkeyLobby = null;
            plugin.getLogger().warning("Latchkey is installed but doesn't offer its lobby the way Waystation expects, so the lobby gate "
                + "uses arrival.lobby-world instead. Update both plugins. (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Where the lobby is now: Latchkey's if it has one, else the configured world. Null for no lobby gate. */
    Location currentLobby() {
        Settings.Arrival a = plugin.settings().arrival;
        if (!a.lobbyGate()) return null;
        if (latchkeyLobby != null && latchkey.isEnabled()) {
            try {
                Location at = (Location) latchkeyLobby.invoke(latchkey);
                if (at != null) return at;
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't ask Latchkey where the lobby is", e);
            }
        }
        if (a.lobbyWorld().isEmpty()) return null;
        World w = Bukkit.getWorld(a.lobbyWorld());
        if (w == null) return null;
        if (a.lobbySpot() == null) return plugin.spawnOf(w);
        return new Location(w, a.lobbySpot()[0], a.lobbySpot()[1], a.lobbySpot()[2]);
    }

    boolean isLobbyWorld(String world) {
        return lobby != null && lobby.getWorld().getName().equals(world) && !world.equals(plugin.settings().stationWorld);
    }

    boolean isLobbyGate(Gate g) {
        return isLobbyWorld(g.world) && g.id.equals("return-" + g.world);
    }

    /** Where a world's return gate goes: by the lobby in the lobby world, by spawn everywhere else. */
    Location anchorFor(World world) {
        return isLobbyWorld(world.getName()) ? lobby : plugin.spawnOf(world);
    }

    /**
     * Rereads the lobby and puts its gate right. A lobby gate that stands too far from the lobby, or the old lobby's
     * gate, is removed so the next sync places it again; nothing else is touched.
     */
    void refresh() {
        Location before = lobby;
        lobby = currentLobby();
        Set<String> redo = new HashSet<>();
        if (before != null && (lobby == null || !before.getWorld().equals(lobby.getWorld()))) {
            Gate old = plugin.store().get("return-" + before.getWorld().getName());
            if (old != null && plugin.settings().arrival.lobbyLabel().equals(old.label)) redo.add(old.id); // back by its spawn
        }
        if (lobby != null && isLobbyWorld(lobby.getWorld().getName())) {
            Gate g = plugin.store().get("return-" + lobby.getWorld().getName());
            boolean moved = before != null && !same(before, lobby);
            if (g != null && needsPlacing(g, plugin.settings().arrival.lobbyLabel(), lobby, moved)) redo.add(g.id);
        }
        if (!redo.isEmpty()) {
            for (String id : redo) {
                Gate g = plugin.store().get(id);
                plugin.store().remove(id);
                // Marked as not done yet, so the next sync places this world's gate again, by the lobby or by spawn.
                if (g != null) plugin.store().clearGenerated(g.world);
            }
            plugin.saveStore();
            plugin.getLogger().info("The lobby moved, so its gate is being placed again.");
        }
        plugin.afterGateChange();
        if (!redo.isEmpty()) plugin.station().sync(true);
    }

    /** How near a freshly placed lobby gate usually stands: the search takes the closest good spot. */
    static final int LOBBY_NEAR = 24;

    /**
     * Whether the lobby world's gate has to be placed again: it was placed as an ordinary return gate (it doesn't carry
     * the lobby label), the lobby moved and the gate isn't near the new spot, or it's out of reach of the lobby.
     */
    static boolean needsPlacing(Gate g, String lobbyLabel, Location lobby, boolean lobbyMoved) {
        if (!lobbyLabel.equals(g.label)) return true;
        if (lobbyMoved && farFrom(g, lobby, LOBBY_NEAR)) return true;
        return farFrom(g, lobby, LOBBY_REACH);
    }

    static boolean farFrom(Gate g, Location at, int reach) {
        double dx = g.x + 0.5 - at.getX(), dz = g.z + 0.5 - at.getZ();
        return dx * dx + dz * dz > (double) reach * reach;
    }

    private static boolean same(Location a, Location b) {
        if (a == null || b == null) return a == b;
        return a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    // --- the welcome inside the station ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) { maybeWelcome(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { maybeWelcome(e.getPlayer()); }

    /** Welcomes someone into the station on their first visit, or after a long time away. Remembered on the player. */
    void maybeWelcome(Player p) {
        Settings s = plugin.settings();
        if (!p.getWorld().getName().equals(s.stationWorld)) return;
        long now = System.currentTimeMillis();
        Long last = p.getPersistentDataContainer().get(seen, PersistentDataType.LONG);
        p.getPersistentDataContainer().set(seen, PersistentDataType.LONG, now);
        if (!s.arrival.welcome() || !due(last, now, s.arrival.welcomeAfterDays())) return;
        // A tick later, so it isn't lost among the messages of arriving.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !p.getWorld().getName().equals(s.stationWorld)) return;
            int[] reach = reach(p);
            for (String line : s.arrival.welcomeText()) {
                p.sendMessage(MiniMessage.miniMessage().deserialize(line,
                    Placeholder.unparsed("worlds", count(reach[0], "world")),
                    Placeholder.unparsed("groups", count(reach[1], "platform"))));
            }
        });
    }

    static boolean due(Long last, long now, int afterDays) {
        return last == null || now - last >= afterDays * 86_400_000L;
    }

    /** How many worlds and platforms this player can actually use from the station. */
    private int[] reach(Player p) {
        Set<String> worlds = new HashSet<>();
        int groups = 0;
        for (Station.Platform platform : plugin.station().platforms()) {
            boolean any = false;
            for (Gate g : platform.gates()) if (plugin.canEnter(p, g)) { worlds.add(g.destination); any = true; }
            if (any) groups++;
        }
        return new int[] {worlds.size(), groups};
    }

    static String count(int n, String thing) {
        if (n == 0) return "no " + thing + "s yet";
        return n + " " + thing + (n == 1 ? "" : "s");
    }
}
