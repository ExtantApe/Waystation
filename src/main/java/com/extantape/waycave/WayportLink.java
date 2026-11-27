package com.extantape.waycave;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Waycave's one line to Wayport (owner, D301): Wayport's own API, asked about one player at a time and answered
 * from that player's own rights. Waycave never runs Wayport's command, never lends a player its own or the
 * console's rights, and never decides for Wayport who may travel.
 *
 * <p>It fails closed. With no Wayport, a Wayport that is off, one without the API (0.4.0 and older), an API older
 * than the version this was written for, or any answer other than a plain yes, the gate is shown to nobody and
 * sends nobody.
 *
 * <p>Wayport is found by name and called by reflection, like Posse, so Waycave builds and runs without it.
 */
final class WayportLink {
    /** The plugin whose service this must be, and the service's type, by name. */
    static final String PLUGIN = "Wayport", SERVICE = "com.extantape.wayport.api.WayportApi";
    /** The API version Waycave was written for: 1 has mayUse and send. */
    static final int NEEDS_VERSION = 1;

    /** A Wayport API that has been checked: new enough, and with the two calls Waycave makes. */
    record Api(Object provider, Method mayUse, Method send) {
        /** The API behind this service, or null when it is missing, too old or not the shape version 1 promises. */
        static Api bind(Class<?> type, Object provider) {
            if (type == null || provider == null || !type.isInstance(provider)) return null;
            try {
                Object version = type.getMethod("apiVersion").invoke(provider);
                if (!(version instanceof Integer v) || v < NEEDS_VERSION) return null;
                Method mayUse = type.getMethod("mayUse", Player.class), send = type.getMethod("send", Player.class);
                if (mayUse.getReturnType() != boolean.class) return null;
                return new Api(provider, mayUse, send);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        /** Wayport's own answer for this player. Anything but a plain yes, an error included, is no. */
        boolean mayUse(Player player) {
            try {
                return Boolean.TRUE.equals(mayUse.invoke(provider, player));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return false;
            }
        }

        /** Asks Wayport to start this player's trip. True only when Wayport says a countdown is running for them. */
        boolean send(Player player) {
            try {
                return started(send.invoke(provider, player));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return false;
            }
        }
    }

    /** Wayport's answer to send, read by name: only a countdown that began, or one already running, counts. */
    static boolean started(Object result) {
        return result instanceof Enum<?> e && (e.name().equals("STARTED") || e.name().equals("ALREADY_COUNTING"));
    }

    /** What Bukkit's services manager holds for Wayport at this moment: the service's type and its provider. */
    record Registered(Class<?> type, Object provider) {}

    private final Supplier<Registered> lookup;

    WayportLink() {
        this(WayportLink::registeredNow);
    }

    /** With another way to look the service up, for tests. */
    WayportLink(Supplier<Registered> lookup) {
        this.lookup = lookup;
    }

    /** Wayport's API as the services manager has it now, or null. Only a service Wayport itself registered counts. */
    static Registered registeredNow() {
        Plugin wayport = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (wayport == null || !wayport.isEnabled()) return null;
        for (RegisteredServiceProvider<?> r : Bukkit.getServicesManager().getRegistrations(wayport)) {
            if (r.getService().getName().equals(SERVICE)) return new Registered(r.getService(), r.getProvider());
        }
        return null;
    }

    /**
     * Looked up from the services manager every time, as Wayport's API asks, and never kept: a Wayport that was
     * disabled, replaced or took its service away a moment ago is not called, and its last answer is not reused.
     */
    private Api current() {
        try {
            Registered now = lookup.get();
            return now == null ? null : Api.bind(now.type(), now.provider());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Is there a Wayport here with the API this gate needs? Without one the gate isn't placed at all. */
    boolean ready() {
        return current() != null;
    }

    /** Would Wayport itself let this player travel? This is the whole of who sees and may use the gate. */
    boolean mayUse(Player player) {
        Api a = current();
        return a != null && a.mayUse(player);
    }

    /** Hands the player to Wayport, which checks them again itself, counts down and sends them. */
    boolean send(Player player) {
        Api a = current();
        return a != null && a.send(player);
    }
}
