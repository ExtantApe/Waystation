package com.extantape.waycave;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Standing in the start chamber's gate to another server. Walking into it starts a visit, and nothing more: the
 * player has to stop in it or within a step of it, and only then is Wayport asked, once, to start their trip. Wayport's countdown
 * cancels at the first step, so asking while they are still walking would cancel it at once. A visit ends when the
 * player walks on or back out, and only a new walk in starts another, so a canceled trip never restarts by itself and a player
 * who logs in or is put inside the gate isn't sent anywhere.
 *
 * <p>Wayport decides who may go. It is asked when the visit starts and again when the player has stopped, and it
 * checks once more itself. A player it wouldn't let through gets no message from here.
 */
final class WayportWalkIn {
    /** How often {@link #pass()} runs, in ticks, and how many looks in a row at the same spot count as stopped. */
    static final long EVERY_TICKS = 2;
    static final int STILL_LOOKS = 4;
    /**
     * How far past the gate's one-block opening a visit still holds, in blocks. Someone who walks in rarely stops
     * dead inside one block: they stop a step beyond it, or half out of it. That still counts. Walking on ends it.
     */
    static final double LINGER = 0.75;
    /** A head turn or a settling step still counts as standing. Well inside what Wayport's countdown allows. */
    static final double DRIFT = 0.02;

    /** Has someone stopped? Fed one position per look. Says yes once, after enough looks at the same spot. */
    static final class Still {
        private double x, y, z;
        private int looks;
        private boolean said;

        boolean look(double nx, double ny, double nz) {
            double dx = nx - x, dy = ny - y, dz = nz - z;
            if (looks == 0 || dx * dx + dy * dy + dz * dz > DRIFT * DRIFT) {
                x = nx;
                y = ny;
                z = nz;
                looks = 1;
                return false;
            }
            if (said || ++looks < STILL_LOOKS) return false;
            said = true;
            return true;
        }
    }

    private final WaycavePlugin plugin;
    private final Map<UUID, Still> visits = new HashMap<>();

    WayportWalkIn(WaycavePlugin plugin) {
        this.plugin = plugin;
    }

    /** The player walked into the gate. Travel has already asked whether they may use it. */
    void begin(Player player) {
        if (visits.putIfAbsent(player.getUniqueId(), new Still()) != null) return;
        var text = plugin.settings().message("wayport-enter");
        if (text != null) player.sendMessage(text);
    }

    void forget(UUID player) {
        visits.remove(player);
    }

    /** Ends the visits of players who stepped out, and hands those who have stopped inside to Wayport. */
    void pass() {
        if (visits.isEmpty()) return;
        Gate gate = plugin.store().get(Gate.WAYPORT_ID);
        for (Iterator<Map.Entry<UUID, Still>> it = visits.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Still> visit = it.next();
            Player player = Bukkit.getPlayer(visit.getKey());
            if (gate == null || player == null || !player.isOnline() || !inside(player, gate)) {
                it.remove();
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) continue;
            Location at = player.getLocation();
            if (!visit.getValue().look(at.getX(), at.getY(), at.getZ())) continue;
            // Asked again now: they may have lost the right to go while they stood here.
            if (plugin.canEnter(player, gate)) {
                player.closeInventory();
                plugin.wayport().send(player);
            }
        }
    }

    private boolean inside(Player player, Gate gate) {
        return player.getWorld().getName().equals(gate.world)
            && gate.trigger(plugin.settings().look(plugin.lookName(gate))).clone().expand(LINGER).contains(player.getLocation().toVector());
    }
}
