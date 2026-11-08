package com.extantape.waystation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The tribute chest. It asks for hearts, or if the player can't spare them, one worn piece that isn't
 * named or enchanted. The menu only shows the choices: nothing is ever placed in it, so nothing can be
 * lost in it. The tribute is taken after the spot is checked again and the move is saved.
 */
final class Mover implements Listener {
    private static final int SIZE = 9, HEARTS_SLOT = 4;

    /** One open chest. It holds the target's chunk until it closes. */
    private static final class Altar implements InventoryHolder {
        final Gate gate;
        final SafeSpots.Spot spot;
        final Chunk held;
        final Map<Integer, EquipmentSlot> pieces = new HashMap<>();
        Inventory inventory;
        boolean paying;

        Altar(Gate gate, SafeSpots.Spot spot, Chunk held) {
            this.gate = gate;
            this.spot = spot;
            this.held = held;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final WaystationPlugin plugin;
    private final Set<UUID> busy = new HashSet<>();

    Mover(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClickChest(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getRightClicked().getScoreboardTags().contains(Renderer.CHEST_TAG)) return;
        event.setCancelled(true);
        for (String tag : event.getRightClicked().getScoreboardTags()) {
            if (tag.startsWith(Renderer.CHEST_TAG + ":")) {
                Gate gate = plugin.store().get(tag.substring(Renderer.CHEST_TAG.length() + 1));
                if (gate != null) open(event.getPlayer(), gate);
            }
        }
    }

    private void open(Player player, Gate gate) {
        Settings s = plugin.settings();
        World world = player.getWorld();
        if (!s.moveEnabled || !s.moveWorlds.contains(world.getName()) || !gate.world.equals(world.getName())) {
            player.sendMessage(s.message("move-disabled"));
            return;
        }
        if (!player.hasPermission(s.movePermission) || plugin.explicitlyDenied(player, "waystation.move." + world.getName()) || !plugin.canEnter(player, gate)) {
            player.sendMessage(s.message("move-no-permission"));
            return;
        }
        long now = System.currentTimeMillis();
        if (now < gate.restUntil) {
            player.sendMessage(s.message("move-resting", "time", timeLeft(gate.restUntil - now)));
            return;
        }
        // Someone who has never slept here reads as the world's own spawn, which is no bed at all.
        Location bed = player.getRespawnLocation();
        Location spawn = plugin.spawnOf(world);
        if (bed == null || !world.equals(bed.getWorld()) || bed.distanceSquared(spawn) < 4 || bed.distanceSquared(world.getSpawnLocation()) < 4) {
            player.sendMessage(s.message("move-no-bed"));
            return;
        }
        if (!canSpareHearts(player) && plainWorn(player).isEmpty()) {
            player.sendMessage(s.message("move-cant-pay", "hearts", String.valueOf(s.heartsFor(world.getName()))));
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            player.sendMessage(s.message("move-busy"));
            return;
        }
        findSpot(player, gate, bed);
    }

    /** Hearts can never kill: you must have more than the price. */
    private boolean canSpareHearts(Player player) {
        return player.getHealth() > plugin.settings().heartsFor(player.getWorld().getName()) * 2;
    }

    private static List<EquipmentSlot> plainWorn(Player player) {
        List<EquipmentSlot> plain = new ArrayList<>();
        for (EquipmentSlot slot : WORN) {
            if (isPlain(player.getInventory().getItem(slot))) plain.add(slot);
        }
        return plain;
    }

    private static boolean isPlain(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.getEnchantments().isEmpty()) return false;
        return !item.hasItemMeta() || !item.getItemMeta().hasDisplayName();
    }

    /** Looks out from the bed's chunk: first away from builds, then anywhere safe. Only land already generated. */
    private void findSpot(Player player, Gate gate, Location bed) {
        Settings s = plugin.settings();
        World world = bed.getWorld();
        int bx = bed.getBlockX() >> 4, bz = bed.getBlockZ() >> 4, r = s.moveSearchChunks;
        List<int[]> order = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (world.isChunkGenerated(bx + dx, bz + dz)) order.add(new int[] {bx + dx, bz + dz});
            }
        }
        order.sort((a, b) -> Integer.compare((a[0] - bx) * (a[0] - bx) + (a[1] - bz) * (a[1] - bz), (b[0] - bx) * (b[0] - bx) + (b[1] - bz) * (b[1] - bz)));
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int[] c : order) loads.add(world.getChunkAtAsync(c[0], c[1], false));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(player.getUniqueId());
            List<Chunk> held = new ArrayList<>();
            for (CompletableFuture<Chunk> load : loads) {
                Chunk chunk = load.getNow(null);
                if (chunk != null && chunk.addPluginChunkTicket(plugin)) held.add(chunk);
            }
            Chunk keep = null;
            try {
                if (!player.isOnline() || !player.getWorld().equals(world)) return;
                List<Gate> others = new ArrayList<>(plugin.store().inWorld(world.getName()));
                others.remove(gate);
                Settings.Look look = s.look(plugin.lookName(gate));
                SafeSpots.Spot found = null;
                boolean claimed = false;
                for (boolean avoidBuilds : new boolean[] {true, false}) {
                    for (Chunk chunk : held) {
                        SafeSpots.Result result = plugin.safeSpots().inChunk(world, chunk.getX(), chunk.getZ(), gate.facing, look, player.getUniqueId(), others, avoidBuilds);
                        if (result.spot() != null) {
                            found = result.spot();
                            keep = chunk;
                            break;
                        }
                        claimed |= result.problem() == SafeSpots.Problem.CLAIMED;
                    }
                    if (found != null) break;
                }
                if (found == null) {
                    player.sendMessage(s.message(claimed ? "move-claimed" : "move-no-spot"));
                    return;
                }
                openMenu(player, new Altar(gate, found, keep));
            } finally {
                for (Chunk chunk : held) {
                    if (chunk != keep) chunk.removePluginChunkTicket(plugin);
                }
            }
        }));
    }

    private void openMenu(Player player, Altar altar) {
        Settings s = plugin.settings();
        altar.inventory = Bukkit.createInventory(altar, SIZE, Component.text("A tribute to the waygate"));
        if (canSpareHearts(player)) {
            ItemStack button = ItemStack.of(Material.REDSTONE);
            button.editMeta(meta -> meta.displayName(Component.text("Give " + s.heartsFor(player.getWorld().getName()) + " hearts", NamedTextColor.RED)));
            altar.inventory.setItem(HEARTS_SLOT, button);
        } else {
            int menuSlot = 2;
            for (EquipmentSlot worn : plainWorn(player)) {
                altar.pieces.put(menuSlot, worn);
                altar.inventory.setItem(menuSlot++, player.getInventory().getItem(worn).clone()); // a picture of it
            }
        }
        player.sendMessage(s.message("move-open"));
        player.openInventory(altar.inventory);
    }

    /** The menu is read-only. A click is a choice, never a move of an item. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Altar altar) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory() || altar.paying) return;
        int slot = event.getRawSlot();
        boolean hearts = slot == HEARTS_SLOT && altar.pieces.isEmpty() && event.getInventory().getItem(HEARTS_SLOT) != null;
        if (!hearts && !altar.pieces.containsKey(slot)) return;
        altar.paying = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pay(player, altar, hearts ? null : altar.pieces.get(slot));
            player.closeInventory();
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Altar) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Altar altar) altar.held.removePluginChunkTicket(plugin);
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Altar) player.closeInventory();
        }
    }

    /** A null piece means hearts. Checks everything again, moves and saves, and only then takes the tribute. */
    private void pay(Player player, Altar altar, EquipmentSlot piece) {
        Settings s = plugin.settings();
        World world = player.getWorld();
        Gate gate = altar.gate;
        PlayerInventory inventory = player.getInventory();
        if (piece == null ? !canSpareHearts(player) : !isPlain(inventory.getItem(piece))) {
            player.sendMessage(s.message("move-paid-too-late"));
            return;
        }
        List<Gate> others = new ArrayList<>(plugin.store().inWorld(world.getName()));
        others.remove(gate);
        if (plugin.store().get(gate.id) != gate || !world.getName().equals(gate.world) || System.currentTimeMillis() < gate.restUntil
            || !plugin.safeSpots().stillGood(world, altar.spot, gate.facing, s.look(plugin.lookName(gate)), player.getUniqueId(), others)) {
            player.sendMessage(s.message("move-spot-gone"));
            return;
        }
        int oldX = gate.x, oldY = gate.y, oldZ = gate.z;
        long oldRest = gate.restUntil;
        gate.x = altar.spot.x();
        gate.y = altar.spot.y();
        gate.z = altar.spot.z();
        gate.restUntil = System.currentTimeMillis() + s.moveRestSeconds * 1000;
        if (!plugin.saveStore()) {
            gate.x = oldX;
            gate.y = oldY;
            gate.z = oldZ;
            gate.restUntil = oldRest;
            player.sendMessage(s.message("travel-failed", "reason", "the move couldn't be saved"));
            return;
        }
        String paid;
        if (piece == null) {
            int hearts = s.heartsFor(world.getName());
            player.setHealth(player.getHealth() - hearts * 2);
            paid = hearts + " hearts";
        } else {
            paid = inventory.getItem(piece).getType().name().toLowerCase(Locale.ROOT);
            inventory.setItem(piece, null);
        }
        plugin.getLogger().info("Tribute: " + player.getName() + " gave " + paid + " to move " + gate.id + " to " + gate.x + " " + gate.y + " " + gate.z);
        plugin.afterGateChange();
        player.sendMessage(s.message("move-answered", "x", String.valueOf(gate.x), "y", String.valueOf(gate.y), "z", String.valueOf(gate.z)));
        var announcement = s.message("move-announce");
        for (Player other : world.getPlayers()) {
            if (!other.equals(player) && announcement != null) other.sendMessage(announcement);
        }
    }

    private static String timeLeft(long ms) {
        long minutes = (ms + 59_999) / 60_000;
        return minutes >= 60 ? (minutes + 59) / 60 + " hours" : minutes + " minutes";
    }
}
