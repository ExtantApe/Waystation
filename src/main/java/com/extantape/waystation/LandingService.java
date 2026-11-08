package com.extantape.waystation;

import java.util.Optional;
import org.bukkit.Location;

/**
 * Where to put someone coming back to the station from a world, for plugins that bring players back themselves.
 * Registered with Bukkit's ServicesManager while Waystation is enabled:
 * {@code Bukkit.getServicesManager().load(LandingService.class)}.
 */
public interface LandingService {
    /**
     * The spot in front of the station gate that leads to this world, facing away from the gate, safe to stand on and
     * clear of the gate itself. Empty if the station isn't built, has no gate to that world, or no safe spot is there.
     * The station lays itself out again when worlds come and go, so ask each time rather than keeping the answer.
     */
    Optional<Location> landing(String world);
}
