package com.extantape.waycave;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Who sees whom in the cave (D194, kids first): players in the cave see each other only where Posse's groups say they
 * may. Anyone Posse won't connect to a player is hidden from them while both are in the cave, and shown again when
 * either leaves. If Posse isn't installed, isn't running or gives anything but a plain yes, nobody sees anyone: the
 * answer fails closed.
 *
 * <p>It uses {@code hideEntity}, so nothing else about a player changes. A player is hidden at the moment they teleport
 * in, before the cave's own pass could, so nobody flashes into view for a moment first. Each pass puts right anything
 * that's changed (a new circle, a new group). Posse is called by method name, so Waycave builds and runs without it.
 */
final class CaveCrowd implements Listener {
    private final WaycavePlugin plugin;
    /** Pairs this class hid: viewer, then the player they can't see. */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    private Method mayReach;
    private Plugin posse;

    CaveCrowd(WaycavePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Whether two players may see each other: both directions must be a plain yes. Pure, so the rule is tested without
     * a server. An unknown answer (null) is a no.
     */
    static boolean mayBeSeen(Boolean aReachesB, Boolean bReachesA) {
        return Boolean.TRUE.equals(aReachesB) && Boolean.TRUE.equals(bReachesA);
    }

    /** Posse's answer for one direction, or null when it can't give one. */
    Boolean askPosse(Player from, Player to) {
        Plugin m = Bukkit.getPluginManager().getPlugin("Posse");
        if (m == null || !m.isEnabled()) return null;
        try {
            if (posse != m || mayReach == null) {
                mayReach = m.getClass().getMethod("mayReach", Player.class, Player.class);
                posse = m;
            }
            Object answer = mayReach.invoke(m, from, to);
            return answer instanceof Boolean b ? b : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    boolean visible(Player a, Player b) {
        return mayBeSeen(askPosse(a, b), askPosse(b, a));
    }

    private World cave() {
        return Bukkit.getWorld(plugin.settings().stationWorld);
    }

    /** Hides or shows every pair in the cave to match Posse now, and shows anyone who has left. */
    void pass() {
        World cave = cave();
        for (UUID viewer : new java.util.ArrayList<>(hidden.keySet())) {
            Player v = Bukkit.getPlayer(viewer);
            if (v == null) {
                hidden.remove(viewer);
                continue;
            }
            for (UUID other : new java.util.ArrayList<>(hidden.get(viewer))) {
                Player o = Bukkit.getPlayer(other);
                boolean both = cave != null && v.getWorld().equals(cave) && o != null && o.getWorld().equals(cave);
                if (!both) show(v, o, other);
            }
        }
        if (cave == null) return;
        for (Player a : cave.getPlayers()) {
            for (Player b : cave.getPlayers()) {
                if (a.equals(b)) continue;
                if (visible(a, b)) show(a, b, b.getUniqueId());
                else hide(a, b);
            }
        }
    }

    private void hide(Player viewer, Player other) {
        viewer.hideEntity(plugin, other);
        hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>()).add(other.getUniqueId());
    }

    private void show(Player viewer, Player other, UUID otherId) {
        Set<UUID> mine = hidden.get(viewer.getUniqueId());
        if (mine == null || !mine.remove(otherId)) return;
        if (mine.isEmpty()) hidden.remove(viewer.getUniqueId());
        if (other != null) viewer.showEntity(plugin, other);
    }

    /** At shutdown: everyone this class hid is shown again, so a reload never leaves anyone invisible. */
    void showAll() {
        for (var e : new HashMap<>(hidden).entrySet()) {
            Player v = Bukkit.getPlayer(e.getKey());
            if (v == null) continue;
            for (UUID id : e.getValue()) {
                Player o = Bukkit.getPlayer(id);
                if (o != null) v.showEntity(plugin, o);
            }
        }
        hidden.clear();
    }

    /** Players about to arrive in the cave are hidden from those there, and the other way round, before they appear. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        World cave = cave();
        if (cave == null || event.getTo() == null || !event.getTo().getWorld().equals(cave)) return;
        hideFromCrowd(event.getPlayer(), cave);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        World cave = cave();
        if (cave != null && event.getPlayer().getWorld().equals(cave)) hideFromCrowd(event.getPlayer(), cave);
        Bukkit.getScheduler().runTask(plugin, this::pass);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        World cave = cave();
        if (cave != null && event.getPlayer().getWorld().equals(cave)) hideFromCrowd(event.getPlayer(), cave);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        hidden.remove(id);
        for (Set<UUID> others : hidden.values()) others.remove(id);
    }

    private void hideFromCrowd(Player arriving, World cave) {
        for (Player other : cave.getPlayers()) {
            if (other.equals(arriving)) continue;
            hide(arriving, other);
            hide(other, arriving);
        }
    }
}
