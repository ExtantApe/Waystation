package hook;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
/** Test-only stand-in for an economy plugin, behind the real Vault interface. Everyone starts with 100. */
final class FakeEconomy {
    static final Map<String, Double> BALANCES = new HashMap<>();
    static void register(org.bukkit.plugin.Plugin plugin) {
        Economy economy = (Economy) Proxy.newProxyInstance(FakeEconomy.class.getClassLoader(), new Class<?>[] {Economy.class}, (p, m, a) -> switch (m.getName()) {
            case "isEnabled" -> true;
            case "getName" -> "FakeEconomy";
            case "format" -> "$" + a[0];
            case "getBalance" -> balance((OfflinePlayer) a[0]);
            case "has" -> balance((OfflinePlayer) a[0]) >= (double) a[1];
            case "withdrawPlayer" -> change((OfflinePlayer) a[0], -(double) a[1]);
            case "depositPlayer" -> change((OfflinePlayer) a[0], (double) a[1]);
            case "hashCode" -> 1;
            case "equals" -> p == a[0];
            case "toString" -> "FakeEconomy";
            default -> throw new UnsupportedOperationException(m.getName());
        });
        plugin.getServer().getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Highest);
    }
    static double balance(OfflinePlayer player) { return BALANCES.getOrDefault(player.getName(), 100.0); }
    static EconomyResponse change(OfflinePlayer player, double by) {
        BALANCES.put(player.getName(), balance(player) + by);
        return new EconomyResponse(Math.abs(by), balance(player), EconomyResponse.ResponseType.SUCCESS, "");
    }
}
