package com.extantape.waystation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.util.BoundingBox;

/**
 * Nothing changes anywhere in the station's world or around a world's gates. There is deliberately no bypass
 * permission, and nothing here looks at who is asking, so operators are stopped like anyone else.
 * The only way round it is protection.enabled in config.yml.
 *
 * Handlers run at HIGHEST so a plugin that un-cancels at HIGH can't get past them.
 */
final class Protection implements Listener {
    private final WaystationPlugin plugin;
    private final Map<String, List<BoundingBox>> zones = new HashMap<>();

    Protection(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    /** Recomputes the protected areas from where the gates are now. */
    void rebuild() {
        zones.clear();
        Settings s = plugin.settings();
        if (!s.protectionEnabled) return;
        for (Gate gate : plugin.store().all()) {
            if (gate.world.equals(s.stationWorld)) continue; // the whole world is protected
            int reach = Math.max(2, s.look(plugin.lookName(gate)).width() / 2 + 1);
            int height = s.look(plugin.lookName(gate)).height();
            int along = gate.alongX() ? reach : 2, across = gate.alongX() ? 2 : reach;
            zones.computeIfAbsent(gate.world, k -> new ArrayList<>()).add(new BoundingBox(gate.x - along, gate.y - 1, gate.z - across,
                gate.x + along + 1, gate.y + height + 1, gate.z + across + 1));
        }
    }

    boolean isProtected(Location at) {
        Settings s = plugin.settings();
        if (!s.protectionEnabled) return false;
        if (at.getWorld().getName().equals(s.stationWorld)) return true;
        List<BoundingBox> boxes = zones.get(at.getWorld().getName());
        if (boxes == null) return false;
        for (BoundingBox box : boxes) if (box.contains(at.getX(), at.getY(), at.getZ())) return true;
        return false;
    }

    private boolean isProtected(Block block) {
        return isProtected(block.getLocation().add(0.5, 0.5, 0.5));
    }

    private void deny(Cancellable event, Player player) {
        event.setCancelled(true);
        var text = plugin.settings().message("protected");
        if (text != null) player.sendActionBar(text);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) { if (isProtected(e.getBlock())) deny(e, e.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) { if (isProtected(e.getBlock())) deny(e, e.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSign(SignChangeEvent e) { if (isProtected(e.getBlock())) deny(e, e.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucket(PlayerBucketEmptyEvent e) { if (isProtected(e.getBlock())) deny(e, e.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucket(PlayerBucketFillEvent e) { if (isProtected(e.getBlock())) deny(e, e.getPlayer()); }

    /** Doors, buttons, chests, pots, farmland: no using anything in there. Walking through gates isn't affected. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null || !isProtected(block)) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.PHYSICAL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getRightClicked().getScoreboardTags().contains(Renderer.CHEST_TAG)) return; // the tribute chest
        if (isProtected(e.getRightClicked().getLocation()) && !(e.getRightClicked() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { if (isProtected(e.getRightClicked().getLocation())) e.setCancelled(true); }

    /** Item frames, armour stands, pets and the like, but people can still play tag. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (!(victim instanceof Player) && isProtected(victim.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakEvent e) { if (isProtected(e.getEntity().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(HangingPlaceEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isProtected); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isProtected); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBurn(BlockBurnEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIgnite(BlockIgniteEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpread(BlockSpreadEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFade(BlockFadeEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onForm(BlockFormEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFlow(BlockFromToEvent e) { if (isProtected(e.getToBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChange(EntityChangeBlockEvent e) { if (isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrow(StructureGrowEvent e) { e.getBlocks().removeIf(state -> isProtected(state.getLocation().add(0.5, 0.5, 0.5))); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDispense(BlockDispenseEvent e) {
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d && isProtected(e.getBlock().getRelative(d.getFacing()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPiston(BlockPistonExtendEvent e) { if (pistonTouches(e.getBlocks(), e.getDirection())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPiston(BlockPistonRetractEvent e) { if (pistonTouches(e.getBlocks(), e.getDirection())) e.setCancelled(true); }

    private boolean pistonTouches(List<Block> moved, org.bukkit.block.BlockFace direction) {
        for (Block block : moved) {
            if (isProtected(block) || isProtected(block.getRelative(direction))) return true;
        }
        return false;
    }

    /** /fill and friends fire no block events, so they're stopped at the command. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Settings s = plugin.settings();
        if (!s.protectionEnabled) return;
        Player player = e.getPlayer();
        String[] words = e.getMessage().substring(1).trim().split("\\s+");
        String command = words[0].toLowerCase(Locale.ROOT);
        command = command.substring(command.indexOf(':') + 1);

        if (command.equals("execute")) {
            // Could aim anywhere. Refuse if it edits at all and any protected area is involved.
            boolean edits = false;
            for (String word : words) {
                String w = word.toLowerCase(Locale.ROOT);
                if (s.guardedCommands.contains(w.substring(w.indexOf(':') + 1))) edits = true;
            }
            if (edits) deny(e, player);
            return;
        }
        if (!s.guardedCommands.contains(command)) return;
        if (player.getWorld().getName().equals(s.stationWorld)) {
            deny(e, player);
            return;
        }
        List<BoundingBox> boxes = zones.get(player.getWorld().getName());
        if (boxes == null) return;
        BoundingBox touched = commandBox(words, command, player.getLocation());
        if (touched == null) {
            deny(e, player); // coordinates we can't read, like ^: don't guess
            return;
        }
        for (BoundingBox box : boxes) {
            if (box.overlaps(touched)) {
                deny(e, player);
                return;
            }
        }
    }

    /** The box a /fill, /setblock or /clone names. Null if it can't be worked out. */
    private static BoundingBox commandBox(String[] words, String command, Location at) {
        int corners = command.equals("setblock") || command.equals("place") ? 1 : 2;
        int start = command.equals("place") ? 3 : 1; // /place feature <id> <pos>
        if (command.equals("clone")) corners = 3;    // two source corners and a destination
        if (command.equals("clone") && words.length < 10) return null;
        if (words.length < start + corners * 3) return command.equals("place") ? box(at, at) : null;
        BoundingBox result = null;
        for (int c = 0; c < corners; c++) {
            Location corner = at.clone();
            try {
                corner.setX(coordinate(words[start + c * 3], at.getX()));
                corner.setY(coordinate(words[start + c * 3 + 1], at.getY()));
                corner.setZ(coordinate(words[start + c * 3 + 2], at.getZ()));
            } catch (NumberFormatException ex) {
                return null;
            }
            result = result == null ? box(corner, corner) : result.union(box(corner, corner));
        }
        if (command.equals("clone")) {
            // Unknown exact destination size in this rough box: union of all corners errs on the safe side.
            result.expand(Math.abs(result.getWidthX()), 0, Math.abs(result.getWidthZ()));
        }
        return result;
    }

    private static BoundingBox box(Location a, Location b) {
        return new BoundingBox(a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX() + 1, b.getBlockY() + 1, b.getBlockZ() + 1);
    }

    private static double coordinate(String word, double here) {
        if (word.startsWith("~")) return here + (word.length() > 1 ? Double.parseDouble(word.substring(1)) : 0);
        return Double.parseDouble(word); // ^ falls out here as NumberFormatException
    }
}
