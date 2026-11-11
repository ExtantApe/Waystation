package com.extantape.waystation;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * On a guarded server, the only way in is a fargate from the sending server (owner, 2026-11-10, D42). While a player's
 * connection is still being configured, before they reach any world, it asks the client for the pass the fargate
 * stored, and admits them only with a valid, unused one for them and for this server. Anything it can't check is a
 * refusal. The whitelist still applies on top. Off unless {@code arrival-guard.enabled} is true.
 */
final class ArrivalGuard implements Listener {
    static final NamespacedKey PASS = new NamespacedKey("waystation", "pass");
    static final long COOKIE_WAIT_MS = 5_000;

    record Config(boolean enabled, String name, String message, TransferPass.Key key) {}

    private final Logger log;
    private final LongSupplier clock;
    private volatile Config config = new Config(false, "", "", new TransferPass.Key(null, "not loaded"));
    /** Players let in this session, so a later reconfiguration doesn't ask again for a pass already used. */
    private final Set<UUID> admitted = ConcurrentHashMap.newKeySet();
    /** Nonces already used, with when they can be forgotten (once their pass would have expired anyway). */
    private final Map<String, Long> used = new ConcurrentHashMap<>();

    ArrivalGuard(Logger log, LongSupplier clock) {
        this.log = log;
        this.clock = clock;
    }

    void apply(Config c) {
        config = c;
        if (c.enabled() && !c.key().usable()) {
            log.severe("arrival-guard is on, but " + c.key().problem() + ". Nobody can join until it's fixed, by design."
                + " Fix: put the same key file in plugins/Waystation/ on both servers, then restart.");
        } else if (c.enabled()) {
            log.info("Arrival guard on: only players coming through a fargate to \"" + c.name() + "\" can join.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        Config c = config;
        if (!c.enabled()) return;
        PlayerConfigurationConnection conn = event.getConnection();
        UUID id = conn.getProfile().getId();
        if (id != null && admitted.contains(id)) return;
        String why = refusal(c, conn, id);
        if (why != null) {
            log.info("Refused " + conn.getProfile().getName() + " at arrival: " + why + ".");
            conn.disconnect(Component.text(c.message()));
            return;
        }
        admitted.add(id);
        conn.storeCookie(PASS, new byte[0]); // used up on this client too
    }

    /** Why this connection may not join, or null to let it in. Every unknown is a refusal. */
    String refusal(Config c, PlayerConfigurationConnection conn, UUID id) {
        if (!c.key().usable()) return "the key isn't usable (" + c.key().problem() + ")";
        if (id == null) return "no player id";
        if (!conn.isTransferred()) return "a direct join, not a transfer";
        byte[] pass;
        try {
            pass = conn.retrieveCookie(PASS).get(COOKIE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "no pass from the client (" + e.getClass().getSimpleName() + ")";
        }
        return judge(c, pass, id);
    }

    /** The pass itself: signature, player, server, time, and used once. */
    String judge(Config c, byte[] pass, UUID id) {
        long now = clock.getAsLong();
        used.values().removeIf(until -> until < now);
        TransferPass.Read r = TransferPass.check(c.key().bytes(), pass, id, c.name(), now);
        if (r.check() != TransferPass.Check.OK) return "pass " + r.check().name().toLowerCase().replace('_', ' ');
        String nonce = HexFormat.of().formatHex(r.nonce());
        if (used.putIfAbsent(nonce, now + 10 * 60_000L) != null) return "pass already used";
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        admitted.remove(e.getPlayer().getUniqueId());
    }
}
