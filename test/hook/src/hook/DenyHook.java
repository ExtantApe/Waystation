package hook;
import com.extantape.waystation.LandingService;
import com.extantape.waystation.WaystationAccessEvent;
import com.extantape.waystation.WaystationHandOverEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
/**
 * Test-only plugin. Proves another plugin can veto a gate through the public event, and stands in for a plugin like
 * Voidwell that takes players into its own world: it can handle the hand-over, ask for a landing spot, and send a
 * death in its world back to that spot. Everything it does is driven from the console, so each test turns it on.
 */
public final class DenyHook extends JavaPlugin implements Listener {
    private boolean handOver;          // handle hand-overs, or leave them for nobody
    private String respawnFrom;        // a death in this world respawns at landing(this world)
    private final Map<UUID, String> diedIn = new HashMap<>();
    private boolean forceRespawn;      // respawn from the server side, for bots whose respawn request can't get through

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        FakeEconomy.register(this);
        getCommand("fakebalance").setExecutor((sender, c, l, a) -> {
            sender.sendMessage("BALANCE " + a[0] + " " + FakeEconomy.balance(getServer().getOfflinePlayer(a[0])));
            return true;
        });
        getCommand("hookhandover").setExecutor((sender, c, l, a) -> {
            handOver = a.length > 0 && a[0].equals("on");
            getLogger().info("HOOK handover " + handOver);
            return true;
        });
        getCommand("hookrespawn").setExecutor((sender, c, l, a) -> {
            respawnFrom = a.length > 0 && !a[0].equals("off") ? a[0] : null;
            getLogger().info("HOOK respawn " + respawnFrom);
            return true;
        });
        getCommand("hookforcerespawn").setExecutor((sender, c, l, a) -> {
            forceRespawn = a.length > 0 && a[0].equals("on");
            getLogger().info("HOOK forcerespawn " + forceRespawn);
            return true;
        });
        getCommand("hookkill").setExecutor((sender, c, l, a) -> {
            org.bukkit.entity.Player p = Bukkit.getPlayerExact(a[0]);
            getLogger().info("HOOK kill " + a[0] + (p == null ? " offline" : " health " + p.getHealth()));
            if (p != null) p.setHealth(0);
            return true;
        });
        getCommand("hooklanding").setExecutor((sender, c, l, a) -> {
            LandingService service = Bukkit.getServicesManager().load(LandingService.class);
            if (service == null) { getLogger().info("LANDING " + a[0] + " no-service"); return true; }
            Optional<Location> spot = service.landing(a[0]);
            getLogger().info("LANDING " + a[0] + " " + spot.map(s -> s.getWorld().getName() + " " + s.getX() + " " + s.getY() + " " + s.getZ()
                + " " + s.getYaw() + " " + s.getPitch()).orElse("none"));
            return true;
        });
    }
    @EventHandler public void on(WaystationAccessEvent e) {
        if (e.getPlayer().getName().equals("hooky") && e.getDestination().equals(System.getProperty("waystation.test.deny", "meadow"))) e.setCancelled(true);
    }
    @EventHandler public void on(WaystationHandOverEvent e) {
        boolean screenOpen = e.getPlayer().getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
        getLogger().info("HANDOVER " + e.getPlayer().getName() + " gate " + e.getGateId() + " dest " + e.getDestination()
            + " handled-before " + e.isHandled() + " screen-open " + screenOpen + " will-handle " + handOver);
        if (!handOver) return;
        World world = Bukkit.getWorld(e.getDestination());
        if (world == null) return;
        e.setHandled(true);
        e.getPlayer().teleport(world.getSpawnLocation().add(0.5, 0, 0.5));
    }
    @EventHandler public void on(PlayerDeathEvent e) {
        diedIn.put(e.getPlayer().getUniqueId(), e.getPlayer().getWorld().getName());
        getLogger().info("DEATH " + e.getPlayer().getName() + " in " + e.getPlayer().getWorld().getName());
        if (forceRespawn) Bukkit.getScheduler().runTaskLater(this, () -> { if (e.getPlayer().isDead()) e.getPlayer().spigot().respawn(); }, 5L);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void on(PlayerRespawnEvent e) {
        String world = diedIn.remove(e.getPlayer().getUniqueId());
        getLogger().info("RESPAWNING " + e.getPlayer().getName() + " died in " + world + ", hook set for " + respawnFrom);
        if (respawnFrom == null || !respawnFrom.equals(world)) return;
        LandingService service = Bukkit.getServicesManager().load(LandingService.class);
        Optional<Location> spot = service == null ? Optional.empty() : service.landing(world);
        getLogger().info("RESPAWN " + e.getPlayer().getName() + " from " + world + " to " + spot.map(Location::toString).orElse("nowhere"));
        spot.ifPresent(e::setRespawnLocation);
    }
}
