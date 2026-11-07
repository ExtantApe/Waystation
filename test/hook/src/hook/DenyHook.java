package hook;
import com.extantape.waystation.WaystationAccessEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
/** Test-only plugin: proves another plugin can veto a gate through the public event. */
public final class DenyHook extends JavaPlugin implements Listener {
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        FakeEconomy.register(this);
        getCommand("fakebalance").setExecutor((sender, c, l, a) -> {
            sender.sendMessage("BALANCE " + a[0] + " " + FakeEconomy.balance(getServer().getOfflinePlayer(a[0])));
            return true;
        });
    }
    @EventHandler public void on(WaystationAccessEvent e) {
        if (e.getPlayer().getName().equals("hooky") && e.getDestination().equals(System.getProperty("waystation.test.deny", "meadow"))) e.setCancelled(true);
    }
}
