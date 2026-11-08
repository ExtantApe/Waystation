package com.extantape.waystation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Walking through gates, and remembering where people were.
 *
 * A gate fires only when a player crosses from outside its trigger area to inside. Arriving in
 * one, logging in inside one, or having one moved onto you does nothing until you step out and back.
 * That edge is what makes teleport loops impossible.
 *
 * Every trip goes through Multiverse's teleporter, naming the player as the teleporter. Multiverse
 * therefore re-checks world access itself, and Multiverse-Inventories swaps inventories on the world
 * change exactly as it does for any other teleport.
 */
final class Travel implements Listener {
    private static final long MESSAGE_GAP_MS = 3000;

    private final WaystationPlugin plugin;
    private final Set<UUID> inFlight = new HashSet<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    /** Where to remember the player when this trip's teleport fires: outside the trigger, not in it. */
    private final Map<UUID, Location> leavingFrom = new HashMap<>();
    /** Last place the player stood that wasn't a gate of any kind. */
    private final Map<UUID, Location> lastGround = new HashMap<>();
    private final Map<UUID, Long> escapeReady = new HashMap<>();
    private final Map<UUID, Long> arrivalGuard = new HashMap<>();
    private static final long ARRIVAL_GUARD_MS = 5000;
    private final Map<UUID, Location> arrivalPin = new HashMap<>();

    Travel(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        Location from = event.getFrom(), to = event.getTo();
        if (!from.getWorld().equals(to.getWorld())) return;
        Gate entered = entered(from, to);
        if (entered != null) {
            enter(player, entered, from);
            return;
        }
        if (event.hasChangedBlock() && !isPortalBlock(to.getBlock().getType()) && !insideAny(to)) {
            lastGround.put(player.getUniqueId(), to.clone());
        }
    }

    /** Riding doesn't fire PlayerMoveEvent. Vehicles and inventories don't mix, so riders get told to hop off. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                Gate entered = entered(event.getFrom(), event.getTo());
                if (entered != null && plugin.canEnter(player, entered)) tell(player, "no-vehicle");
            }
        }
    }

    /** The gate whose trigger area this step crossed into, if any. */
    private Gate entered(Location from, Location to) {
        for (Gate p : plugin.store().inWorld(to.getWorld().getName())) {
            BoundingBox box = p.trigger(plugin.settings().look(plugin.lookName(p)));
            if (box.contains(from.toVector())) continue;
            if (box.contains(to.toVector())) return p;
            Vector step = to.toVector().subtract(from.toVector());
            double length = step.length();
            if (length > 0.5 && box.rayTrace(from.toVector(), step.normalize(), length) != null) return p; // fast flyers
        }
        return null;
    }

    private boolean insideAny(Location at) {
        for (Gate p : plugin.store().inWorld(at.getWorld().getName())) {
            if (p.trigger(plugin.settings().look(plugin.lookName(p))).contains(at.toVector())) return true;
        }
        return false;
    }

    private static boolean isPortalBlock(Material type) {
        return type == Material.NETHER_PORTAL || type == Material.END_PORTAL || type == Material.END_GATEWAY;
    }

    private void enter(Player player, Gate gate, Location outside) {
        UUID id = player.getUniqueId();
        if (player.getGameMode() == GameMode.SPECTATOR || player.isDead() || player.isSleeping()) return;
        if (inFlight.contains(id) || System.currentTimeMillis() < readyAt.getOrDefault(id, 0L)) return;

        if (gate.isFargate()) {
            if (plugin.canEnter(player, gate)) fargateOut(player, gate, outside);
            return; // otherwise it does nothing at all: no message, so it stays hidden
        }

        // Re-checked here, on entry, whatever the player could or couldn't see.
        if (!plugin.isActive(gate)) {
            tell(player, plugin.isDead(gate) ? "gate-dead" : "destination-closed");
            return;
        }
        if (!plugin.canEnter(player, gate)) {
            if (plugin.isForSale(player, gate.destination) && plugin.canSee(player, gate)) {
                player.sendMessage(plugin.settings().message("gate-locked", "world", gate.destination,
                    "cost", plugin.payments().text(plugin.settings().worlds.get(gate.destination).price())));
            } // otherwise nothing: it isn't there for them

            return;
        }
        if (player.isInsideVehicle()) {
            tell(player, "no-vehicle");
            return;
        }
        World world = Bukkit.getWorld(gate.destination);
        leavingFrom.put(id, outside.clone());
        String command = plugin.settings().enterCommand(gate.destination);
        if (command != null) { // the world's own plugin knows where this player belongs
            readyAt.put(id, System.currentTimeMillis() + plugin.settings().travelCooldownTicks * 50L);
            player.closeInventory();
            player.performCommand(command);
            Bukkit.getScheduler().runTaskLater(plugin, () -> leavingFrom.remove(id), 60L);
            return;
        }
        Location atGate = arrivalGateSpot(world);
        if (atGate != null) {
            arrivalGuard.put(id, System.currentTimeMillis() + ARRIVAL_GUARD_MS);
            arrivalPin.put(id, atGate);
            Bukkit.getScheduler().runTaskLater(plugin, () -> arrivalPin.remove(id), 100L); // long enough for any chunk load
            go(player, atGate, plugin.spawnOf(world));
            return;
        }
        Location last = gate.destination.equals(gate.world) ? null : plugin.data().lastSpot(id, world);
        go(player, last != null ? last : plugin.spawnOf(world), plugin.spawnOf(world));
    }

    /**
     * In survival worlds you arrive at the world's gate, so where it stands really matters. Elsewhere you
     * return to your last spot. Set worlds.<name>.arrival to gate or last to choose otherwise.
     */
    private Location arrivalGateSpot(World world) {
        Settings.WorldEntry entry = plugin.settings().worlds.get(world.getName());
        String arrival = entry == null ? null : entry.arrival();
        boolean atGate = arrival != null ? arrival.equalsIgnoreCase("gate") : plugin.isSurvivalWorld(world);
        Gate gate = atGate ? plugin.store().get("return-" + world.getName()) : null;
        if (gate == null) return null;
        Location spot = new Location(world, gate.x + 0.5 + gate.facing.getModX(), gate.y, gate.z + 0.5 + gate.facing.getModZ());
        spot.setDirection(gate.facing.getDirection());
        return spot;
    }

    /**
     * Multiverse-Inventories can send someone on to their last location straight after they arrive in
     * a world (apply-last-location-for-all-teleports). A trip that's meant to end at the gate ends there.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSentOnward(PlayerTeleportEvent event) {
        Location pin = arrivalPin.get(event.getPlayer().getUniqueId());
        if (pin == null || event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN || event.getTo() == null) return;
        if (event.getFrom().getWorld().equals(pin.getWorld()) && event.getTo().getWorld().equals(pin.getWorld())) event.setTo(pin.clone());
    }

    /** Nobody can move a gate somewhere to ambush whoever comes through next. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && System.currentTimeMillis() < arrivalGuard.getOrDefault(player.getUniqueId(), 0L)) {
            event.setCancelled(true);
        }
    }

    /** Off to another server. Everything stays here: no world changes, so no inventory swap. */
    private void fargateOut(Player player, Gate gate, Location outside) {
        if (player.isInsideVehicle()) {
            tell(player, "no-vehicle");
            return;
        }
        Settings.FargateTarget target = plugin.settings().fargates.get(gate.fargateName());
        player.closeInventory();
        plugin.data().setFargateReturn(player.getUniqueId(), outside);
        player.sendMessage(plugin.settings().message("fargate-leaving", "name", target.label()));
        player.transfer(target.host(), target.port());
    }

    /** From a OneBlock post: to that world's gate in the station, or the station's spawn if it has none. */
    void toStation(Player player, String fromWorld) {
        UUID id = player.getUniqueId();
        World station = Bukkit.getWorld(plugin.settings().stationWorld);
        if (station == null || inFlight.contains(id)) return;
        player.leaveVehicle();
        Gate gate = plugin.store().get("station-" + fromWorld);
        Location spawn = plugin.spawnOf(station);
        if (gate == null) {
            go(player, spawn, null);
            return;
        }
        Location front = new Location(station, gate.x + 0.5 + gate.facing.getModX() * 2, gate.y, gate.z + 0.5 + gate.facing.getModZ() * 2);
        front.setDirection(gate.facing.getDirection());
        arrivalPin.put(id, front);
        Bukkit.getScheduler().runTaskLater(plugin, () -> arrivalPin.remove(id), 100L);
        go(player, front, spawn);
    }

    /** The escape command: straight to the station's spawn, off any ride. */
    void escape(Player player) {
        Settings s = plugin.settings();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis(), ready = escapeReady.getOrDefault(id, 0L);
        if (now < ready) {
            player.sendMessage(s.message("escape-cooldown", "seconds", String.valueOf((ready - now + 999) / 1000)));
            return;
        }
        World station = Bukkit.getWorld(s.stationWorld);
        if (station == null || inFlight.contains(id)) {
            if (station == null) tell(player, "destination-closed");
            return;
        }
        player.leaveVehicle();
        escapeReady.put(id, now + s.escapeCooldownSeconds * 1000);
        go(player, plugin.spawnOf(station), null);
    }

    /** Tries the target, then the fallback if there is one, then gives up with the player unmoved. */
    private void go(Player player, Location target, Location fallback) {
        UUID id = player.getUniqueId();
        inFlight.add(id);
        player.closeInventory(); // puts anything on the cursor back before the inventories swap
        int cx = target.getBlockX() >> 4, cz = target.getBlockZ() >> 4;
        target.getWorld().getChunkAtAsync(cx, cz).whenComplete((chunk, error) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    inFlight.remove(id);
                    leavingFrom.remove(id);
                    return;
                }
                plugin.worlds().teleport(player, target,
                    () -> onMain(() -> {
                        inFlight.remove(id);
                        leavingFrom.remove(id);
                        readyAt.put(id, System.currentTimeMillis() + plugin.settings().travelCooldownTicks * 50L);
                    }),
                    reason -> onMain(() -> {
                        if (fallback != null && player.isOnline()) {
                            go(player, fallback, null);
                            return;
                        }
                        inFlight.remove(id);
                        leavingFrom.remove(id);
                        player.sendMessage(plugin.settings().message("travel-failed", "reason", reason));
                    }));
            }));
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Any trip out of a world by any means sets the last spot there. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        remember(event.getPlayer(), event.getFrom(), event.getTo(), false);
    }

    /** Nether and End portals: remember the step before the portal block, or you'd land back in it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        remember(event.getPlayer(), event.getFrom(), event.getTo(), true);
    }

    private void remember(Player player, Location from, Location to, boolean throughBlockPortal) {
        if (to == null || from.getWorld().equals(to.getWorld())) return;
        UUID id = player.getUniqueId();
        Location spot = leavingFrom.get(id);
        if (spot == null && throughBlockPortal) {
            Location ground = lastGround.get(id);
            if (ground == null || !ground.getWorld().equals(from.getWorld())) return;
            spot = ground;
        }
        plugin.data().remember(id, spot != null ? spot : from);
    }

    /** Don't send anyone back to where they died. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        plugin.data().forget(player.getUniqueId(), player.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        refreshSoon(player);
        plugin.station().askAdmin(player);
        // Back from another server: step out of the fargate, to where they walked in from.
        Location back = plugin.data().takeFargateReturn(player.getUniqueId());
        if (back != null && back.getWorld().equals(player.getWorld())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.teleportAsync(back);
            });
        }
    }

    /** Now, for the common case, and again once LuckPerms has caught up with the player's new world. */
    private void refreshSoon(Player player) {
        plugin.renderer().refresh(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.renderer().refresh(player);
        }, 3L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastGround.remove(id);
        lastMessage.remove(id);
        readyAt.remove(id);
        arrivalGuard.remove(id);
        arrivalPin.remove(id);
    }

    private void tell(Player player, String key) {
        long now = System.currentTimeMillis();
        if (now - lastMessage.getOrDefault(player.getUniqueId(), 0L) < MESSAGE_GAP_MS) return;
        lastMessage.put(player.getUniqueId(), now);
        var text = plugin.settings().message(key);
        if (text != null) player.sendMessage(text);
    }
}
