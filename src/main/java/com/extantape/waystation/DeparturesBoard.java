package com.extantape.waystation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Departures Board (arrival flow, plan phase 3): beside the station's centrepiece, a board listing the worlds each
 * player can reach, grouped by platform exactly like the gates. Each player sees their own board, so nobody learns
 * which worlds anyone else can use, and it's rewritten as soon as their access changes.
 *
 * <p>One text display per player in the station, shown only to that player. Never saved, so a crash leaves none.
 */
final class DeparturesBoard implements Listener {
    /** One line of the board: a platform heading or a world under it. */
    record Line(boolean heading, String text) {}

    private final WaystationPlugin plugin;
    private final Map<UUID, TextDisplay> boards = new HashMap<>();
    private final Map<UUID, String> shown = new HashMap<>();

    DeparturesBoard(WaystationPlugin plugin) { this.plugin = plugin; }

    /** Where the board stands: a few blocks in front of the centrepiece, at eye height. Null without a station. */
    Location spot() {
        Settings s = plugin.settings();
        World station = Bukkit.getWorld(s.stationWorld);
        if (station == null || plugin.station().platforms().isEmpty()) return null;
        int[] c = plugin.station().center(station);
        return new Location(station, c[0] + 0.5, s.stationY + 2.2, c[1] + 0.5 + s.board.distance());
    }

    /** The once-a-second pass: every player in the station has an up-to-date board, and nobody else has one. */
    void pass() {
        Settings s = plugin.settings();
        Location at = s.board.enabled() ? spot() : null;
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (at == null || p == null || !p.getWorld().equals(at.getWorld())) drop(id);
        }
        if (at == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        for (Player p : at.getWorld().getPlayers()) {
            String text = render(s.board.title(), lines(p), s.board.empty());
            TextDisplay board = boards.get(p.getUniqueId());
            if (board == null || !board.isValid()) {
                board = spawn(at, p);
                boards.put(p.getUniqueId(), board);
                shown.remove(p.getUniqueId());
            }
            if (!text.equals(shown.get(p.getUniqueId()))) {
                board.text(MiniMessage.miniMessage().deserialize(text));
                shown.put(p.getUniqueId(), text);
            }
        }
    }

    /** The worlds this player can use from the station, by platform, in the platforms' own order. */
    List<Line> lines(Player p) {
        List<Line> out = new ArrayList<>();
        for (Station.Platform platform : plugin.station().platforms()) {
            List<Line> worlds = new ArrayList<>();
            for (Gate g : platform.gates()) {
                if (g.isFargate()) continue;
                String name = plugin.station().labelOf(g.destination);
                if (plugin.canEnter(p, g)) worlds.add(new Line(false, name));
                else if (plugin.isActive(g) && plugin.isForSale(p, g.destination) && plugin.canSee(p, g)) worlds.add(new Line(false, name + " <gray>(buy at the gate)"));
            }
            if (worlds.isEmpty()) continue;
            out.add(new Line(true, "Platform " + platform.number() + " · " + platform.label()));
            out.addAll(worlds);
        }
        return out;
    }

    /** The board's text, in MiniMessage. Headings in gold, worlds indented under them. */
    static String render(String title, List<Line> lines, String empty) {
        StringBuilder b = new StringBuilder(title);
        if (lines.isEmpty()) return b.append("\n<gray>").append(empty).toString();
        for (Line l : lines) {
            b.append('\n');
            if (l.heading()) b.append("\n<gold>").append(MiniMessage.miniMessage().escapeTags(l.text())).append("</gold>");
            else b.append("<white>").append(l.text()).append("</white>");
        }
        return b.toString();
    }

    private TextDisplay spawn(Location at, Player viewer) {
        TextDisplay board = at.getWorld().spawn(at, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.addScoreboardTag(Renderer.TAG);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setAlignment(TextDisplay.TextAlignment.LEFT);
            e.setLineWidth(260);
            e.setBrightness(new Display.Brightness(15, 15));
            Labels.style(e, plugin.settings().labelBacking);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));
            e.text(Component.empty());
        });
        viewer.showEntity(plugin, board);
        return board;
    }

    private void drop(UUID id) {
        TextDisplay board = boards.remove(id);
        shown.remove(id);
        if (board != null) board.remove();
    }

    void removeAll() { for (UUID id : new ArrayList<>(boards.keySet())) drop(id); }

    @EventHandler public void onQuit(PlayerQuitEvent e) { drop(e.getPlayer().getUniqueId()); }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { drop(e.getPlayer().getUniqueId()); }
}
