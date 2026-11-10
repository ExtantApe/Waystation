package com.extantape.waystation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The wall map (arrival flow, plan phase 3): a top-down picture of the station on a wall of maps behind the centrepiece,
 * with each player's own "you are here" marker. Each player's picture shows only the gates they can see, so it tells
 * nobody about worlds they can't reach.
 *
 * <p>Maps are drawn by Paper per player. The frames are invisible, fixed and never saved. The map ids are kept in
 * {@code wallmap.yml}, so a restart reuses them instead of making new map files.
 */
final class WallMap {
    static final int TILE = 128;

    private final WaystationPlugin plugin;
    private final File idsFile;
    private final List<MapView> views = new ArrayList<>();
    private final List<GlowItemFrame> frames = new ArrayList<>();
    private final Map<UUID, Cached> pictures = new HashMap<>();
    private final List<Tile> tileRenderers = new ArrayList<>();
    private record Cached(int tick, String key, StationPicture picture) {}

    WallMap(WaystationPlugin plugin) {
        this.plugin = plugin;
        this.idsFile = new File(plugin.getDataFolder(), "wallmap.yml");
    }

    /** The once-a-second pass: frames up where the settings say, or gone if the map is off. */
    void pass() {
        Settings.WallMapSettings s = plugin.settings().wallMap;
        World station = Bukkit.getWorld(plugin.settings().stationWorld);
        if (!s.enabled() || station == null || plugin.station().platforms().isEmpty()) { removeFrames(); return; }
        int[] c = plugin.station().center(station);
        int x0 = c[0] - s.tiles() / 2, z = c[1] - s.distance(), y0 = plugin.settings().stationY + 1;
        if (!station.isChunkLoaded(x0 >> 4, z >> 4)) { frames.clear(); return; }
        ensureViews(station, s.tiles() * s.tiles());
        boolean intact = frames.size() == views.size() && frames.stream().allMatch(f -> f.isValid() && f.getWorld().equals(station));
        if (intact) return;
        removeFrames();
        for (int row = 0; row < s.tiles(); row++) for (int col = 0; col < s.tiles(); col++) {
            MapView view = views.get(row * s.tiles() + col);
            Location at = new Location(station, x0 + col, y0 + (s.tiles() - 1 - row), z);
            ItemStack map = ItemStack.of(Material.FILLED_MAP);
            map.editMeta(MapMeta.class, m -> m.setMapView(view));
            frames.add(station.spawn(at, GlowItemFrame.class, f -> {
                f.setFacingDirection(BlockFace.SOUTH, true);
                f.setItem(map, false);
                f.setFixed(true);
                f.setVisible(false);
                f.setInvulnerable(true);
                f.setPersistent(false);
                f.addScoreboardTag(Renderer.TAG);
            }));
        }
    }

    /** Loads the saved map ids, or makes the maps once and saves their ids. Each gets this plugin's renderer only. */
    private void ensureViews(World station, int count) {
        if (views.size() == count) return;
        views.clear();
        tileRenderers.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(idsFile);
        List<Integer> ids = y.getIntegerList("map-ids");
        boolean changed = false;
        int tiles = plugin.settings().wallMap.tiles();
        for (int i = 0; i < count; i++) {
            MapView view = i < ids.size() ? Bukkit.getMap(ids.get(i)) : null;
            if (view == null) { view = Bukkit.createMap(station); changed = true; }
            for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            view.setLocked(true);
            Tile tile = new Tile(i % tiles, i / tiles, tiles);
            tileRenderers.add(tile);
            view.addRenderer(tile);
            views.add(view);
        }
        if (changed) {
            y.set("map-ids", views.stream().map(MapView::getId).toList());
            try { y.save(idsFile); } catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Couldn't save wallmap.yml", e); }
        }
    }

    void removeFrames() {
        for (GlowItemFrame f : frames) if (f.isValid()) f.remove();
        frames.clear();
    }

    /** This player's picture: rebuilt at most once a tick, and only reused while the gates they can see are the same. */
    StationPicture pictureFor(Player p, int tiles) {
        int tick = Bukkit.getCurrentTick();
        Cached c = pictures.get(p.getUniqueId());
        if (c != null && c.tick() == tick) return c.picture();
        World station = Bukkit.getWorld(plugin.settings().stationWorld);
        List<StationPicture.Mark> marks = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (Station.Platform platform : plugin.station().platforms()) {
            for (Gate g : platform.gates()) {
                if (g.isFargate() || !plugin.canSee(p, g)) continue;
                int width = plugin.settings().look(plugin.lookName(g)).width();
                marks.add(new StationPicture.Mark(g.x, g.z, g.alongX(), width, platform.number()));
                key.append(g.id).append('@').append(g.x).append(',').append(g.z).append(';');
            }
        }
        int[] centre = plugin.station().center(station);
        key.append(centre[0]).append(',').append(centre[1]);
        StationPicture picture = c != null && c.key().contentEquals(key) ? c.picture()
            : StationPicture.draw(TILE * tiles, centre[0], centre[1], marks, plugin.settings().tileDepth);
        pictures.put(p.getUniqueId(), new Cached(tick, key.toString(), picture));
        return picture;
    }

    /** Drops what was kept for a player who left, so nothing about them stays in memory. */
    void forget(UUID player) {
        pictures.remove(player);
        for (Tile t : tileRenderers) t.drawn.remove(player);
    }

    /** Draws one tile of the picture, per player, with their marker if they stand over this tile. */
    private final class Tile extends MapRenderer {
        private final int col, row, tiles;
        private final Map<UUID, StationPicture> drawn = new HashMap<>();

        Tile(int col, int row, int tiles) {
            super(true);
            this.col = col;
            this.row = row;
            this.tiles = tiles;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (!player.getWorld().getName().equals(plugin.settings().stationWorld)) return;
            StationPicture pic = pictureFor(player, tiles);
            if (drawn.get(player.getUniqueId()) != pic) {
                for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++)
                    canvas.setPixelColor(x, y, new Color(pic.at(col * TILE + x, row * TILE + y)));
                drawn.put(player.getUniqueId(), pic);
            }
            var cursors = canvas.getCursors();
            while (cursors.size() > 0) cursors.removeCursor(cursors.getCursor(0));
            int px = pic.toPixelX(player.getLocation().getX()) - col * TILE, py = pic.toPixelZ(player.getLocation().getZ()) - row * TILE;
            if (px < 0 || py < 0 || px >= TILE || py >= TILE) return;
            byte dir = (byte) Math.floorMod(Math.round(player.getLocation().getYaw() / 22.5f), 16);
            cursors.addCursor(new MapCursor((byte) (px * 2 - 128), (byte) (py * 2 - 128), dir, MapCursor.Type.PLAYER, true));
        }
    }
}
