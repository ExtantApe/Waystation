package com.extantape.waycave;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
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
 * The Trails board (D194): beside the start chamber's monument, a board listing the worlds each player can reach, under
 * a heading for each chamber, like the gates. A world a player could reach before and can't now stays on it, reading
 * (trail closed). Each player sees their own board, so nobody learns which worlds anyone else can use, and a world a
 * player has never been able to use is never named to them. It's rewritten as soon as their access changes.
 *
 * <p>One text display per player in the cave, shown only to that player. Never saved, so a crash leaves none.
 */
final class TrailsBoard implements Listener {
    /** One line of the board: a chamber heading or a world under it. */
    record Line(boolean heading, String text) {}

    private final WaycavePlugin plugin;
    private final Map<UUID, TextDisplay> boards = new HashMap<>();
    private final Map<UUID, String> shown = new HashMap<>();
    /** The click spot over each board, shown only to the board's owner. Clicking it opens the buying menu. */
    private final Map<UUID, Interaction> hits = new HashMap<>();

    TrailsBoard(WaycavePlugin plugin) { this.plugin = plugin; }

    /** Where the board stands: a few blocks in front of the monument, at eye height. Null without a cave. */
    Location spot() {
        Settings s = plugin.settings();
        World station = Bukkit.getWorld(s.stationWorld);
        if (station == null || !plugin.cave().exists()) return null;
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
            Interaction hit = hits.get(p.getUniqueId());
            if (hit == null || !hit.isValid()) hits.put(p.getUniqueId(), spawnHit(at, p));
            if (!text.equals(shown.get(p.getUniqueId()))) {
                board.text(MiniMessage.miniMessage().deserialize(text));
                shown.put(p.getUniqueId(), text);
            }
        }
    }

    /** What the board says about one world to one player. */
    enum State { OPEN, FOR_SALE, CLOSED, HIDDEN }

    /**
     * The state of a world for a player: open if they can use it, for sale if they could buy their way in, closed if
     * they could use it once and can't now (and its group isn't one that always stays hidden), hidden otherwise. Pure,
     * so the rule is tested without a server: nothing about a world they never had is ever shown.
     */
    static State stateOf(boolean canEnter, boolean forSale, boolean hadBefore, boolean hiddenGroup) {
        if (canEnter) return State.OPEN;
        if (forSale) return State.FOR_SALE;
        return hadBefore && !hiddenGroup ? State.CLOSED : State.HIDDEN;
    }

    /** One line for a world in a state, or null for none. */
    static Line lineFor(String name, State state) {
        return switch (state) {
            case OPEN -> new Line(false, name);
            case FOR_SALE -> new Line(false, name + " <gray>(for sale: click the board)");
            case CLOSED -> new Line(false, name + " <gray>(trail closed)");
            case HIDDEN -> null;
        };
    }

    /** The worlds this player can use from the cave, by chamber, in the chambers' own order. Remembers the open ones. */
    List<Line> lines(Player p) {
        List<Line> out = new ArrayList<>();
        java.util.Set<String> had = plugin.data().trails(p.getUniqueId());
        java.util.Set<String> open = new java.util.TreeSet<>();
        for (Station.Platform platform : plugin.station().platforms()) {
            List<Line> worlds = new ArrayList<>();
            boolean hiddenGroup = plugin.settings().sealed.hiddenGroups().contains(platform.group());
            for (Gate g : platform.gates()) {
                if (g.leavesServer()) continue;
                String name = plugin.station().labelOf(g.destination);
                boolean enter = plugin.canEnter(p, g);
                boolean sale = !enter && plugin.canBuy(p, g.destination) && plugin.payments().available(plugin.settings().worlds.get(g.destination).price());
                if (enter) open.add(g.destination);
                Line line = lineFor(name, stateOf(enter, sale, had.contains(g.destination), hiddenGroup));
                if (line != null) worlds.add(line);
            }
            if (worlds.isEmpty()) continue;
            out.add(new Line(true, platform.label()));
            out.addAll(worlds);
        }
        plugin.data().rememberTrails(p.getUniqueId(), open);
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

    /** A box over the board, big enough for a child to hit. Not saved, and only its owner is sent it. */
    private Interaction spawnHit(Location at, Player viewer) {
        Location base = at.clone().subtract(0, 1.8, 0);
        Interaction hit = at.getWorld().spawn(base, Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.addScoreboardTag(Renderer.TAG);
            e.addScoreboardTag(BuyMenu.TAG + ":" + viewer.getUniqueId());
            e.setInteractionWidth(4f);
            e.setInteractionHeight(4f);
            e.setResponsive(true);
        });
        viewer.showEntity(plugin, hit);
        return hit;
    }

    private void drop(UUID id) {
        Interaction hit = hits.remove(id);
        if (hit != null) hit.remove();
        TextDisplay board = boards.remove(id);
        shown.remove(id);
        if (board != null) board.remove();
    }

    void removeAll() { for (UUID id : new ArrayList<>(boards.keySet())) drop(id); }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        drop(e.getPlayer().getUniqueId());
        plugin.wallMap().forget(e.getPlayer().getUniqueId());
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { drop(e.getPlayer().getUniqueId()); }
}
