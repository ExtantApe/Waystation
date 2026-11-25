package com.extantape.waycave;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The buying menu (D284: nobody types a command to buy). A player clicks the Trails board in the cave and sees the gates
 * they could buy, each with its price and what they have. Clicking one asks "Buy it?" and a second click on Yes buys it.
 * The same offers are read out in chat, each with a name to click, for players who use a screen reader or a narrator.
 *
 * <p>Fail closed: an offer exists only for a world that is for sale to this player, has a gate they can see (the rule
 * the board and the gates use, so a child never meets another group's gate) and can be paid for right now (money needs
 * Vault and an economy plugin). Every click checks all of that again, and the menu never holds an item a player can take.
 */
final class BuyMenu implements Listener {
    static final String TAG = "waycave-buyboard";
    /** A chest has at most six rows. Anyone with more than this for sale sees the first ones. */
    static final int MAX_OFFERS = 54;
    static final int YES = 11, SHOWN = 13, NO = 15, CONFIRM_SIZE = 27;

    /** One thing for sale, as this player sees it. */
    record Offer(String world, String name, String cost, String have, boolean affordable) {}

    /** What a click in the menu means. */
    enum Action { CHOOSE, YES, NO, NONE }
    record Click(Action action, int index) {}

    /** Rows needed for this many offers, from one to six. */
    static int rows(int offers) { return Math.max(1, Math.min(6, (offers + 8) / 9)); }

    /** A click in the list of offers. A slot with no offer in it does nothing. */
    static Click listClick(int slot, int offers) {
        return slot >= 0 && slot < Math.min(offers, MAX_OFFERS) ? new Click(Action.CHOOSE, slot) : new Click(Action.NONE, -1);
    }

    /** A click on the "buy it?" screen: the Yes square, the No square, nothing else. */
    static Click confirmClick(int slot) {
        return slot == YES ? new Click(Action.YES, -1) : slot == NO ? new Click(Action.NO, -1) : new Click(Action.NONE, -1);
    }

    /** The rule for an offer, in one place so it can be tested: all three must hold. */
    static boolean eligible(boolean forSale, boolean gateVisible, boolean payable) { return forSale && gateVisible && payable; }

    /** An open menu. Holds only names, not the player's items, and is read-only. */
    private static final class Holder implements InventoryHolder {
        final UUID player;
        final String chosen; // the world on the "buy it?" screen, or null on the list
        final List<String> worlds;
        Inventory inventory;
        boolean done;

        Holder(UUID player, String chosen, List<String> worlds) { this.player = player; this.chosen = chosen; this.worlds = worlds; }

        @Override public Inventory getInventory() { return inventory; }
    }

    private final WaycavePlugin plugin;

    BuyMenu(WaycavePlugin plugin) { this.plugin = plugin; }

    /** The offers this player has right now, in the cave's own order, one per world. */
    List<Offer> offers(Player player) {
        List<Offer> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Station.Platform platform : plugin.station().platforms()) {
            for (Gate g : platform.gates()) {
                if (g.leavesServer() || !seen.add(g.destination) || out.size() >= MAX_OFFERS) continue;
                Settings.WorldEntry entry = plugin.settings().worlds.get(g.destination);
                if (entry == null || !eligible(plugin.canBuy(player, g.destination), true, plugin.payments().available(entry.price()))) continue;
                out.add(new Offer(g.destination, plugin.station().plainName(g.destination), plugin.payments().text(entry.price()),
                    plugin.payments().haveText(player, entry.price()), plugin.payments().canPay(player, entry.price())));
            }
        }
        return out;
    }

    /** The board was clicked: the list of what's for sale, and the same in chat. */
    void open(Player player) {
        Settings s = plugin.settings();
        List<Offer> offers = offers(player);
        if (offers.isEmpty()) {
            player.sendMessage(s.message("buy-menu-none"));
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), null, offers.stream().map(Offer::world).toList());
        holder.inventory = Bukkit.createInventory(holder, rows(offers.size()) * 9, s.message("buy-menu-title"));
        for (int i = 0; i < offers.size(); i++) holder.inventory.setItem(i, item(offers.get(i), s, "buy-menu-choose"));
        player.sendMessage(s.message("buy-menu-intro"));
        for (Offer offer : offers) {
            Component choose = s.message("buy-menu-choose-link", "world", offer.name());
            choose = choose.clickEvent(ClickEvent.callback(who -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) confirm(player, offer.world());
            }), ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()));
            player.sendMessage(s.message("buy-menu-line", Placeholder.component("choose", choose),
                "world", offer.name(), "cost", offer.cost(), "have", offer.have()));
        }
        player.openInventory(holder.inventory);
    }

    /** The "buy it?" screen for one world, after checking it is still on offer. */
    void confirm(Player player, String world) {
        Settings s = plugin.settings();
        Offer offer = offers(player).stream().filter(o -> o.world().equals(world)).findFirst().orElse(null);
        if (offer == null) {
            player.sendMessage(s.message("buy-menu-gone", "world", plugin.station().plainName(world)));
            player.closeInventory();
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), world, List.of(world));
        holder.inventory = Bukkit.createInventory(holder, CONFIRM_SIZE, s.message("buy-menu-confirm-title", "world", offer.name()));
        holder.inventory.setItem(SHOWN, item(offer, s, null));
        holder.inventory.setItem(NO, pane(Material.RED_STAINED_GLASS_PANE, s.message("buy-menu-no")));
        if (offer.affordable()) {
            holder.inventory.setItem(YES, pane(Material.LIME_STAINED_GLASS_PANE, s.message("buy-menu-yes", "world", offer.name(), "cost", offer.cost())));
        } else {
            holder.inventory.setItem(YES, pane(Material.GRAY_STAINED_GLASS_PANE, s.message("buy-menu-short")));
        }
        player.sendMessage(s.message(offer.affordable() ? "buy-menu-confirm" : "buy-menu-cant", "world", offer.name(), "cost", offer.cost(), "have", offer.have()));
        player.openInventory(holder.inventory);
    }

    private ItemStack item(Offer offer, Settings s, String clickKey) {
        ItemStack stack = ItemStack.of(Material.ENDER_PEARL);
        stack.editMeta(meta -> {
            meta.displayName(Component.text(offer.name()).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(s.message("buy-menu-item-cost", "cost", offer.cost()));
            lore.add(s.message("buy-menu-item-have", "have", offer.have()));
            lore.add(s.message(offer.affordable() ? (clickKey == null ? "buy-menu-item-ok" : clickKey) : "buy-menu-item-short"));
            meta.lore(lore);
        });
        return stack;
    }

    private ItemStack pane(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        return stack;
    }

    // ---- events ----

    private void clicked(Player player, Entity entity) {
        if (!entity.getScoreboardTags().contains(TAG + ":" + player.getUniqueId())) return;
        if (!player.hasPermission("waycave.buy")) {
            player.sendMessage(plugin.settings().message("buy-menu-no-permission"));
            return;
        }
        open(player);
    }

    @EventHandler public void onUse(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getRightClicked().getScoreboardTags().contains(TAG + ":" + event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        clicked(event.getPlayer(), event.getRightClicked());
    }

    @EventHandler public void onHit(PrePlayerAttackEntityEvent event) {
        if (!event.getAttacked().getScoreboardTags().contains(TAG + ":" + event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        clicked(event.getPlayer(), event.getAttacked());
    }

    /** Read-only: a click is a choice, never a move of an item. */
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory() || holder.done || !player.getUniqueId().equals(holder.player)) return;
        int slot = event.getRawSlot();
        if (holder.chosen == null) {
            Click c = listClick(slot, holder.worlds.size());
            if (c.action() == Action.CHOOSE) {
                holder.done = true;
                String world = holder.worlds.get(c.index());
                Bukkit.getScheduler().runTask(plugin, () -> confirm(player, world));
            }
            return;
        }
        Click c = confirmClick(slot);
        if (c.action() == Action.NO) {
            holder.done = true;
            Bukkit.getScheduler().runTask(plugin, () -> open(player));
        } else if (c.action() == Action.YES) {
            holder.done = true; // one click buys once, however fast the second comes
            String world = holder.chosen;
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                plugin.purchase(player, world);
            });
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) player.closeInventory();
        }
    }
}
