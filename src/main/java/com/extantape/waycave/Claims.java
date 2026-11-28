package com.extantape.waycave;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Asks GriefPrevention who owns a spot. GriefPrevention is optional and reached by reflection,
 * so the jar needs no compile dependency on it. If it's installed but can't be asked, we answer
 * "claimed": a failed check must never let a gate land on someone's land.
 */
final class Claims {
    private final Logger log;

    Claims(Logger log) {
        this.log = log;
    }

    boolean active() {
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        return gp != null && gp.isEnabled();
    }

    /** GriefPrevention's lookup, found once per class: a gate search asks about thousands of columns. */
    private Class<?> lookupClass;
    private Method lookup;
    private long lastWarned;

    private Method claimAt(Class<?> dataStore) throws NoSuchMethodException {
        if (dataStore == lookupClass) return lookup;
        for (Method m : dataStore.getMethods()) {
            if (m.getName().equals("getClaimAt") && m.getParameterCount() == 3
                && m.getParameterTypes()[0] == Location.class && m.getParameterTypes()[1] == boolean.class) {
                lookupClass = dataStore;
                lookup = m;
                return m;
            }
        }
        throw new NoSuchMethodException("DataStore.getClaimAt(Location, boolean, Claim)");
    }

    /**
     * True if the spot is in a claim the player neither owns nor is trusted to build in. Admin claims count. With no
     * player (a gate Waycave places by itself, for nobody in particular), every claim counts.
     */
    boolean foreignClaim(Location at, UUID player) {
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null) return false;
        try {
            Object dataStore = gp.getClass().getField("dataStore").get(gp);
            Method getClaimAt = claimAt(dataStore.getClass());
            Object claim = getClaimAt.invoke(dataStore, at, true, null);
            if (claim == null) return false;
            if (player == null) return true;
            Object owner = claim.getClass().getMethod("getOwnerID").invoke(claim);
            if (player.equals(owner)) return false;
            // The owner may have trusted this player. Building trust is what a gate on their land amounts to.
            for (Method m : claim.getClass().getMethods()) {
                if (m.getName().equals("hasExplicitPermission") && m.getParameterTypes()[0] == UUID.class) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object build = Enum.valueOf((Class) m.getParameterTypes()[1], "Build");
                    return !(boolean) m.invoke(claim, player, build);
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A gate search asks thousands of times in one go: say it once a minute, not once a column.
            long now = System.currentTimeMillis();
            if (now - lastWarned > 60_000) {
                lastWarned = now;
                log.log(Level.SEVERE, "Couldn't ask GriefPrevention about a claim; treating the spot as claimed", e);
            }
            return true;
        }
    }
}
