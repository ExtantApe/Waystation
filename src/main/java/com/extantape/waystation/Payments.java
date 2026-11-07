package com.extantape.waystation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Takes payment in XP, items and money. Money goes through Vault, reached by reflection so the jar
 * has no dependency on it, and whichever economy plugin sits behind Vault. With no economy plugin, a
 * price in money simply can't be paid.
 */
final class Payments {
    private final WaystationPlugin plugin;

    Payments(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    private static Class<?> economyType() throws ClassNotFoundException {
        return Class.forName("net.milkbowl.vault.economy.Economy", true, Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader());
    }

    private Object economy() {
        try {
            Class<?> type = economyType();
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(type);
            return provider == null ? null : provider.getProvider();
        } catch (ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private Object call(Object economy, String method, Object... args) throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i] instanceof OfflinePlayer ? OfflinePlayer.class : double.class;
        return economyType().getMethod(method, types).invoke(economy, args);
    }

    boolean canPay(Player player, Settings.Cost cost) {
        if (player.getLevel() < cost.xpLevels()) return false;
        for (Map.Entry<Material, Integer> item : cost.items().entrySet()) {
            if (!player.getInventory().containsAtLeast(new ItemStack(item.getKey()), item.getValue())) return false;
        }
        if (cost.money() > 0) {
            Object economy = economy();
            if (economy == null) return false;
            try {
                return (boolean) call(economy, "has", player, cost.money());
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().warning("Couldn't ask the economy plugin for a balance: " + e);
                return false;
            }
        }
        return true;
    }

    /** Call canPay first. */
    void pay(Player player, Settings.Cost cost) {
        if (cost.xpLevels() > 0) player.giveExpLevels(-cost.xpLevels());
        for (Map.Entry<Material, Integer> item : cost.items().entrySet()) {
            player.getInventory().removeItem(new ItemStack(item.getKey(), item.getValue()));
        }
        if (cost.money() > 0) money(player, "withdrawPlayer", cost.money());
    }

    void refund(Player player, Settings.Cost cost) {
        if (cost.xpLevels() > 0) player.giveExpLevels(cost.xpLevels());
        for (Map.Entry<Material, Integer> item : cost.items().entrySet()) {
            player.getInventory().addItem(new ItemStack(item.getKey(), item.getValue()))
                .values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        }
        if (cost.money() > 0) money(player, "depositPlayer", cost.money());
    }

    private void money(Player player, String method, double amount) {
        try {
            call(economy(), method, player, amount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().severe("Economy call " + method + " failed for " + player.getName() + " (" + amount + "): " + e);
        }
    }

    /** "2 ender pearl and 5 XP levels and $500" */
    String text(Settings.Cost cost) {
        List<String> parts = new ArrayList<>();
        cost.items().forEach((material, amount) -> parts.add(amount + " " + material.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
        if (cost.xpLevels() > 0) parts.add(cost.xpLevels() + " XP levels");
        if (cost.money() > 0) {
            String shown = String.valueOf(cost.money());
            Object economy = economy();
            if (economy != null) {
                try {
                    shown = String.valueOf(economyType().getMethod("format", double.class).invoke(economy, cost.money()));
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().warning("Couldn't ask the economy plugin to format money: " + e);
                }
            }
            parts.add(shown);
        }
        return parts.isEmpty() ? "nothing" : String.join(" and ", parts);
    }
}
