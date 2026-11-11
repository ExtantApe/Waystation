package com.extantape.waystation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.scheduler.BukkitTask;

/**
 * The return command: a fargate you don't have to walk to. The player stands still for a few seconds, and is then sent
 * to a fargate destination. Moving, damage, a harmful effect or a teleport cancels it, and each cancel says why and how
 * to try again. Each player has at most one countdown, and nothing about them changes unless the transfer happens.
 */
final class ReturnCountdown implements Listener {
    /** How far a player can drift from where they stood, in blocks, before it counts as moving. Head turns don't count. */
    static final double MOVE_TOLERANCE = 0.1;

    // Sound cues, so the countdown can be followed without watching the action bar.
    private static final Sound TICK = Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 0.5f, 1.0f);
    private static final Sound SENDING = Sound.sound(Key.key("block.note_block.chime"), Sound.Source.MASTER, 0.7f, 1.5f);
    private static final Sound CANCELLED = Sound.sound(Key.key("block.note_block.bass"), Sound.Source.MASTER, 0.7f, 0.6f);

    private final WaystationPlugin plugin;
    private final Map<UUID, Countdown> active = new HashMap<>();

    ReturnCountdown(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    /** One player's countdown, with the settings it started under, so a reload can't change it halfway. */
    private static final class Countdown {
        final UUID player;
        final Location start;
        final Settings.Return config;
        final Settings.FargateTarget target;
        BukkitTask task;
        int ticks;
        int secondsLeft;
        boolean checking;

        Countdown(UUID player, Location start, Settings.Return config, Settings.FargateTarget target) {
            this.player = player;
            this.start = start;
            this.config = config;
            this.target = target;
            this.secondsLeft = config.countdownSeconds();
        }
    }

    /** The command. */
    void request(Player player) {
        Settings s = plugin.settings();
        Settings.Return config = s.returnCommand;
        String problem = config.problem(s.fargates);
        if (problem != null) {
            // Admins get the reason, so they can fix it without reading the console.
            if (player.hasPermission("waystation.admin")) send(player, "return-not-set-up-admin", config, null, 0, "problem", problem);
            else send(player, "return-not-set-up", config, null, 0);
            return;
        }
        Settings.FargateTarget target = s.fargates.get(config.destination());
        if (active.containsKey(player.getUniqueId())) {
            send(player, "return-already", config, target, 0);
            return;
        }
        if (player.isInsideVehicle()) {
            player.sendMessage(s.message("no-vehicle"));
            return;
        }
        if (!target.protocols().contains(plugin.clientProtocol(player))) {
            send(player, "return-wrong-version", config, target, 0);
            return;
        }
        Countdown c = new Countdown(player.getUniqueId(), player.getLocation(), config, target);
        active.put(c.player, c);
        send(player, "return-starting", config, target, c.secondsLeft);
        actionBar(player, "return-countdown", c, c.secondsLeft);
        play(player, c, TICK, 1.0f);
        c.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(c), 1L, 1L);
        // Check the destination now too, so nobody waits out a countdown for a server that's down.
        ping(c, up -> {
            if (!up) end(c, "return-offline");
        });
    }

    private void tick(Countdown c) {
        if (active.get(c.player) != c) {
            c.task.cancel();
            return;
        }
        Player player = Bukkit.getPlayer(c.player);
        if (player == null || !player.isOnline()) {
            end(c, null);
            return;
        }
        if (moved(c.start, player.getLocation())) {
            end(c, c.start.getWorld() == player.getWorld() ? "return-cancelled-moved" : "return-cancelled-teleported");
            return;
        }
        if (c.checking) return;
        if (++c.ticks % 20 != 0) return;
        c.secondsLeft--;
        if (c.secondsLeft > 0) {
            actionBar(player, "return-countdown", c, c.secondsLeft);
            play(player, c, TICK, 1.0f + 0.15f * (c.config.countdownSeconds() - c.secondsLeft)); // rises a little each second
            return;
        }
        c.checking = true;
        actionBar(player, "return-checking", c, 0);
        ping(c, up -> {
            if (!up) {
                end(c, "return-offline");
                return;
            }
            Player p = Bukkit.getPlayer(c.player);
            if (p == null || !p.isOnline()) {
                end(c, null);
                return;
            }
            stop(c);
            if (!plugin.givePass(p, c.target)) {
                end(c, "return-unavailable");
                return;
            }
            send(p, "return-sending", c.config, c.target, 0);
            play(p, c, SENDING, 1.0f);
            p.closeInventory();
            // Coming back through this server's own fargate arrival, they land where they stood.
            plugin.data().setFargateReturn(p.getUniqueId(), p.getLocation());
            plugin.getLogger().info("Sending " + p.getName() + " to " + c.target.name() + " (" + c.target.host() + ":" + c.target.port() + ")");
            p.transfer(c.target.host(), c.target.port());
        });
    }

    static boolean moved(Location start, Location now) {
        if (start.getWorld() != now.getWorld()) return true;
        return start.distanceSquared(now) > MOVE_TOLERANCE * MOVE_TOLERANCE;
    }

    /** Pings off the main thread, then hands the answer back on it if the countdown is still running. */
    private void ping(Countdown c, Consumer<Boolean> then) {
        String host = c.config.checkHost().isEmpty() ? c.target.host() : c.config.checkHost();
        int port = c.config.checkPort() > 0 ? c.config.checkPort() : c.target.port();
        int timeout = c.config.checkTimeoutMs();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean up = StatusPing.isUp(host, port, timeout);
            if (!up) plugin.getLogger().info("The return command's destination didn't answer at " + host + ":" + port);
            if (!plugin.isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (active.get(c.player) == c) then.accept(up);
                });
            } catch (RuntimeException _) {
                // The plugin was disabled in between: onDisable has already ended every countdown.
            }
        });
    }

    private void stop(Countdown c) {
        if (c.task != null) c.task.cancel();
        active.remove(c.player, c);
    }

    /** Ends a countdown without a transfer. A null message key means say nothing. */
    private void end(Countdown c, String messageKey) {
        stop(c);
        Player player = Bukkit.getPlayer(c.player);
        if (player != null && player.isOnline()) {
            player.sendActionBar(Component.empty());
            if (messageKey != null) {
                send(player, messageKey, c.config, c.target, 0);
                play(player, c, CANCELLED, 1.0f);
            }
        }
    }

    private void cancel(Player player, String messageKey) {
        Countdown c = active.get(player.getUniqueId());
        if (c != null) end(c, messageKey);
    }

    /** Ends every countdown, for a stop or a reload. */
    void cancelAll() {
        for (Countdown c : List.copyOf(active.values())) {
            try {
                end(c, "return-cancelled-shutdown");
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't tell a player their return was cancelled", e);
            }
        }
        active.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) cancel(player, "return-cancelled-damaged");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED && event.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        PotionEffect effect = event.getNewEffect();
        if (effect != null && effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) cancel(player, "return-cancelled-effect");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        cancel(event.getPlayer(), "return-cancelled-teleported");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        cancel(event.getPlayer(), "return-cancelled-damaged");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Countdown c = active.get(event.getPlayer().getUniqueId());
        if (c != null) stop(c);
    }

    private void play(Player player, Countdown c, Sound sound, float pitch) {
        if (c.config.sounds()) player.playSound(Sound.sound(sound).pitch(pitch).build(), Sound.Emitter.self());
    }

    private void send(Player player, String key, Settings.Return config, Settings.FargateTarget target, int seconds, String... pairs) {
        Component text = render(key, config, target, seconds, pairs);
        if (text != null) player.sendMessage(text);
    }

    private void actionBar(Player player, String key, Countdown c, int seconds) {
        Component text = render(key, c.config, c.target, seconds);
        if (text != null) player.sendActionBar(text);
    }

    private Component render(String key, Settings.Return config, Settings.FargateTarget target, int seconds, String... pairs) {
        // The destination's label can be MiniMessage itself, so it's parsed; everything else is plain text.
        TagResolver destination = Placeholder.parsed("destination", target == null ? "the other server" : target.label());
        String[] all = new String[pairs.length + 4];
        all[0] = "seconds";
        all[1] = Integer.toString(seconds);
        all[2] = "command";
        all[3] = "/" + config.command();
        System.arraycopy(pairs, 0, all, 4, pairs.length);
        return plugin.settings().message(key, destination, all);
    }
}
