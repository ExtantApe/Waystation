package com.extantape.waystation;

import com.extantape.signalbox.Answer;
import com.extantape.signalbox.Describe;
import com.extantape.signalbox.Errors;
import com.extantape.signalbox.Request;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /gate: create, move, link, list, remove, test, addworld, relayout, reload and more. Works from the console. Every
 * subcommand also takes {@code --json <id>} and then answers with exactly one {@code ea-result} line in the shared
 * Signalbox format; the slow ones (addworld, relayout, reseed, enlarge) answer when the station is done.
 */
final class AdminCommand implements BasicCommand {
    static final List<String> SUBCOMMANDS =
        List.of("help", "list", "station", "create", "fargate", "move", "link", "remove", "test", "addworld", "relayout", "reseed", "enlarge", "reload", "describe");
    static final List<String> FACINGS = List.of("north", "south", "east", "west");
    static final int PAGE = 25, MAX_PAGE = 100;

    private final WaystationPlugin plugin;

    AdminCommand(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    /** What /gate describe lists. Every node is waystation.admin.<subcommand>, all granted by waystation.admin. */
    static List<Describe.Command> described() {
        var gate = Describe.Arg.of("gate", "string");
        List<Describe.Arg> where = List.of(Describe.Arg.of("world", "string").asOptional(), Describe.Arg.of("x", "int").asOptional(),
            Describe.Arg.of("y", "int").asOptional(), Describe.Arg.of("z", "int").asOptional(), Describe.Arg.choice("facing", FACINGS).asOptional());
        List<Describe.Arg> create = new ArrayList<>(List.of(Describe.Arg.of("id", "string"), Describe.Arg.of("destination", "string")));
        create.addAll(where);
        List<Describe.Arg> fargate = new ArrayList<>(List.of(Describe.Arg.of("id", "string"), Describe.Arg.of("destination", "string")));
        fargate.addAll(where);
        List<Describe.Arg> move = new ArrayList<>(List.of(gate));
        move.addAll(where);
        return List.of(
            cmd("list", Describe.Arg.of("world", "string").asOptional(), Describe.Arg.of("limit", "int").asOptional(), Describe.Arg.of("cursor", "string").asOptional()),
            cmd("station"),
            new Describe.Command("create", node("create"), create, List.of(), false),
            new Describe.Command("fargate", node("fargate"), fargate, List.of(), false),
            new Describe.Command("move", node("move"), move, List.of(), false),
            cmd("link", gate, Describe.Arg.of("other", "string")),
            cmd("remove", gate),
            cmd("test", gate, Describe.Arg.of("player", "player").asOptional()),
            cmd("addworld", Describe.Arg.of("world", "string")),
            cmd("relayout"),
            cmd("reseed", Describe.Arg.of("seed", "int").asOptional()),
            cmd("enlarge"),
            cmd("reload"),
            cmd("describe"));
    }

    private static Describe.Command cmd(String name, Describe.Arg... args) {
        return new Describe.Command(name, node(name), List.of(args), List.of(), false);
    }

    private static String node(String sub) { return "waystation.admin." + sub; }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Request r;
        try {
            r = Request.parse(args);
        } catch (Request.Bad bad) {
            if (bad.id != null) Answer.send(sender::sendMessage, Answer.error(bad.id, bad.code, bad.getMessage()));
            else sender.sendMessage(Component.text(bad.getMessage(), NamedTextColor.RED));
            return;
        }
        Out o = r.json() ? new JsonOut(sender, r.id()) : new PlainOut(sender);
        List<String> t = new ArrayList<>(r.tokens());
        if (r.freeText() != null) t.add(r.freeText()); // only addworld's label uses it
        String typed = t.isEmpty() ? "help" : t.get(0).toLowerCase(Locale.ROOT);
        String sub = SUBCOMMANDS.contains(typed) ? typed : r.json() ? null : "help";
        if (sub == null) {
            o.fail(Errors.UNKNOWN_COMMAND, "There's no /gate " + typed + ". Try /gate describe.");
            return;
        }
        // waystation.admin grants every waystation.admin.<subcommand>. Grant them one by one for a narrower role.
        if (!allowed(sender, sub)) {
            o.fail(Errors.NO_PERMISSION, "You don't have permission for that.");
            return;
        }
        String[] a = t.toArray(String[]::new);
        switch (sub) {
            case "list" -> list(o, a);
            case "station" -> station(o);
            case "create" -> create(o, a);
            case "fargate" -> fargate(o, a);
            case "move" -> move(o, a);
            case "link" -> link(o, a);
            case "remove" -> remove(o, a);
            case "test" -> test(o, a);
            case "addworld" -> addWorld(o, a);
            case "relayout" -> { if (none(o, a)) relayout(o); }
            case "reseed" -> reseed(o, a);
            case "enlarge" -> { if (none(o, a)) enlarge(o); }
            case "reload" -> { if (none(o, a)) { String said = plugin.reload(); o.put("message", said); o.info(said); o.ok(); } }
            case "describe" -> describe(o);
            default -> help(o);
        }
    }

    /** These nodes default to false, which would shut the console out too, so it's let in by name. */
    private static boolean allowed(CommandSender sender, String sub) {
        if (sub.equals("help") || sub.equals("describe")) return !(sender instanceof Player) || sender.hasPermission("waystation.admin") || anyAdmin(sender);
        return !(sender instanceof Player) || sender.hasPermission("waystation.admin") || sender.hasPermission(node(sub));
    }

    private static boolean anyAdmin(CommandSender sender) {
        return SUBCOMMANDS.stream().anyMatch(s -> sender.hasPermission(node(s)));
    }

    private boolean none(Out o, String[] args) {
        if (args.length <= 1) return true;
        o.fail(Errors.BAD_ARGUMENT, "/gate " + args[0] + " takes no arguments.");
        return false;
    }

    private void help(Out o) {
        for (String line : List.of(
            "/gate list [world]",
            "/gate station   (seed, plan and platforms)",
            "/gate create <id> <destination world> [<world> <x> <y> <z> <facing>]",
            "/gate fargate <id> <destination in config> [<world> <x> <y> <z> <facing>]",
            "/gate move <id> [<world> <x> <y> <z> [facing]]",
            "/gate link <id> <id>   (each then leads to the other's world)",
            "/gate remove <id>",
            "/gate test <id> [player]",
            "/gate addworld <world> [label...]",
            "/gate relayout   (lays out the station again)",
            "/gate reseed [number]   (a whole new station)",
            "/gate enlarge   (this station, with more room)",
            "/gate reload",
            "/gate describe   (every command, for scripts; add --json <id> to any command)")) {
            o.info(line);
        }
        o.ok();
    }

    private void describe(Out o) {
        if (o instanceof JsonOut j) {
            j.answer(Answer.ok(j.id, Describe.body("Waystation", plugin.getPluginMeta().getVersion(), described())));
            return;
        }
        help(o);
    }

    private void station(Out o) {
        Station st = plugin.station();
        o.put("seed", st.seed());
        o.put("plan", st.planName());
        o.put("enlarged", plugin.store().growth());
        o.put("blocked", st.blockedSlots());
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (Station.Platform p : st.platforms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", p.number());
            m.put("group", p.group());
            m.put("gates", p.gates().stream().map(g -> g.id).toList());
            platforms.add(m);
        }
        o.put("platforms", platforms);
        o.info("Station seed " + st.seed() + ", plan " + st.planName() + ", enlarged " + plugin.store().growth());
        if (!st.blockedSlots().isEmpty()) o.warn("No safe floor for: " + String.join(", ", st.blockedSlots()) + ". /gate enlarge or /gate reseed");
        for (Station.Platform platform : st.platforms()) {
            o.info("Platform " + platform.number() + " (" + platform.group() + "): " + String.join(", ", platform.gates().stream().map(p -> p.id).toList()));
        }
        o.ok();
    }

    private void list(Out o, String[] args) {
        if (o instanceof JsonOut j) {
            listJson(j, args);
            return;
        }
        String only = args.length > 1 ? args[1] : null;
        station(new PlainOut(o.sender()) {
            @Override void ok() {}
        });
        for (Gate p : plugin.store().all()) {
            if (only != null && !only.equals(p.world)) continue;
            o.info(p.id + ": " + p.world + " " + p.x + " " + p.y + " " + p.z + " " + p.facing.name().toLowerCase(Locale.ROOT)
                + " -> " + p.destination + (plugin.isDead(p) ? " (DEAD)" : plugin.isActive(p) ? "" : " (closed)") + (p.link != null ? " link " + p.link : ""));
        }
    }

    /** list [world] [limit] [cursor], in pages: the cursor is the next gate's position, as handed out. */
    private void listJson(JsonOut j, String[] args) {
        String only = null;
        int limit = PAGE, from = 0, i = 1;
        if (args.length > i && !args[i].matches("\\d+")) only = args[i++];
        try {
            if (args.length > i) limit = Integer.parseInt(args[i++]);
            if (args.length > i) {
                if (!args[i].matches("g[0-9]{1,6}")) throw new NumberFormatException();
                from = Integer.parseInt(args[i++].substring(1));
            }
        } catch (NumberFormatException e) {
            j.fail(Errors.BAD_ARGUMENT, "Usage: /gate list [world] [limit 1-" + MAX_PAGE + "] [cursor from the last page]");
            return;
        }
        if (args.length > i || limit < 1 || limit > MAX_PAGE) {
            j.fail(Errors.BAD_ARGUMENT, "Usage: /gate list [world] [limit 1-" + MAX_PAGE + "] [cursor from the last page]");
            return;
        }
        String world = only;
        List<Gate> gates = plugin.store().all().stream().filter(p -> world == null || world.equals(p.world)).toList();
        if (from > gates.size()) {
            j.fail(Errors.BAD_ARGUMENT, "That cursor is past the end. Start again without one.");
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Gate p : gates.subList(from, Math.min(gates.size(), from + limit))) items.add(asMap(p));
        int next = from + items.size();
        j.answer(Answer.page(j.id, items, next < gates.size() ? "g" + next : null));
    }

    private Map<String, Object> asMap(Gate p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("world", p.world);
        m.put("x", p.x);
        m.put("y", p.y);
        m.put("z", p.z);
        m.put("facing", p.facing.name().toLowerCase(Locale.ROOT));
        if (p.isFargate()) m.put("fargate", p.fargateName());
        else m.put("destination", p.destination);
        m.put("state", state(p));
        m.put("link", p.link);
        Station.Platform platform = plugin.station().platformOf(p);
        m.put("platform", platform == null ? null : platform.group());
        return m;
    }

    private String state(Gate p) {
        return plugin.isDead(p) ? "dead" : plugin.isActive(p) ? "open" : "closed";
    }

    private void create(Out o, String[] args) {
        if (args.length < 3) {
            o.fail(Errors.BAD_ARGUMENT, "Usage: /gate create <id> <destination world> [<world> <x> <y> <z> <facing>]");
            return;
        }
        String id = args[1], destination = args[2];
        if (!validId(o, id)) return;
        if (plugin.store().get(id) != null) {
            o.fail("already-exists", "There's already a gate called " + id);
            return;
        }
        if (Bukkit.getWorld(destination) == null) {
            o.fail(Errors.NOT_FOUND, "No such loaded world: " + destination);
            return;
        }
        Location at = position(o, args, 3);
        if (at == null) return;
        BlockFace facing = args.length > 7 ? parseFacing(o, args[7]) : facingOf(at);
        if (facing == null) return;
        Gate g = new Gate(id, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), facing, destination);
        plugin.store().put(g);
        commit(o, "Created " + id, g);
    }

    /** A gate to another server. Destinations are defined under fargate-destinations in config.yml. */
    private void fargate(Out o, String[] args) {
        if (args.length < 3) {
            o.fail(Errors.BAD_ARGUMENT, "Usage: /gate fargate <id> <destination> [<world> <x> <y> <z> <facing>]");
            return;
        }
        if (!validId(o, args[1])) return;
        if (plugin.store().get(args[1]) != null) {
            o.fail("already-exists", "There's already a gate called " + args[1]);
            return;
        }
        if (!plugin.settings().fargates.containsKey(args[2])) {
            o.fail(Errors.NOT_FOUND, "No such destination under fargate-destinations: " + args[2]);
            return;
        }
        Location at = position(o, args, 3);
        if (at == null) return;
        BlockFace facing = args.length > 7 ? parseFacing(o, args[7]) : facingOf(at);
        if (facing == null) return;
        Gate g = new Gate(args[1], at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), facing, Gate.FARGATE_PREFIX + args[2]);
        plugin.store().put(g);
        if (!Bukkit.getServer().isAcceptingTransfers()) {
            o.warn("It stays hidden until accepts-transfers=true is set in server.properties.");
        }
        commit(o, "Created fargate " + args[1], g);
    }

    private void move(Out o, String[] args) {
        Gate p = gate(o, args, 1);
        if (p == null) return;
        Location at = position(o, args, 2);
        if (at == null) return;
        BlockFace facing = args.length > 6 ? parseFacing(o, args[6]) : args.length > 2 ? p.facing : facingOf(at);
        if (facing == null) return;
        p.world = at.getWorld().getName();
        p.x = at.getBlockX();
        p.y = at.getBlockY();
        p.z = at.getBlockZ();
        p.facing = facing;
        p.restUntil = 0; // an admin's move also ends any rest after a tribute
        commit(o, "Moved " + p.id, p);
    }

    private void link(Out o, String[] args) {
        Gate a = gate(o, args, 1), b = a == null ? null : gate(o, args, 2);
        if (b == null) return;
        if (a == b) {
            o.fail(Errors.BAD_ARGUMENT, "A gate can't be linked to itself.");
            return;
        }
        a.destination = b.world;
        a.link = b.id;
        b.destination = a.world;
        b.link = a.id;
        o.put("gates", List.of(asMap(a), asMap(b)));
        commit(o, "Linked " + a.id + " (to " + a.destination + ") and " + b.id + " (to " + b.destination + ")", null);
    }

    private void remove(Out o, String[] args) {
        Gate p = gate(o, args, 1);
        if (p == null) return;
        plugin.store().remove(p.id);
        for (Gate other : plugin.store().all()) if (p.id.equals(other.link)) other.link = null;
        o.put("removed", p.id);
        if (p.isFargate()) {
            commit(o, "Removed " + p.id + ".", null);
        } else if (plugin.isDead(p)) {
            plugin.store().clearGenerated(p.destination); // the world is gone: if it ever returns, it gets a new pair
            commit(o, "Removed " + p.id + ".", null);
        } else {
            commit(o, "Removed " + p.id + ". It stays gone until /gate addworld " + p.destination + " brings it back.", null);
        }
        plugin.station().sync(false);
    }

    /** Reports what a gate is doing, and what it means for one player or for everyone online. */
    private void test(Out o, String[] args) {
        Gate p = gate(o, args, 1);
        if (p == null) return;
        if (args.length > 3) {
            o.fail(Errors.BAD_ARGUMENT, "Usage: /gate test <id> [player]");
            return;
        }
        World world = Bukkit.getWorld(p.world);
        Settings.Look look = plugin.settings().look(plugin.lookName(p));
        Station.Platform platform = plugin.station().platformOf(p);
        o.put("gate", asMap(p));
        o.info(p.id + " at " + p.world + " " + p.x + " " + p.y + " " + p.z + " facing " + p.facing.name().toLowerCase(Locale.ROOT));
        o.info("destination " + p.destination + ", " + state(p).toUpperCase(Locale.ROOT).replace("OPEN", "open") + ", platform "
            + (platform == null ? "none" : platform.group()));
        if (world == null) {
            o.fail("world-not-loaded", "world isn't loaded");
            return;
        }
        List<Player> players = new ArrayList<>();
        if (args.length > 2) {
            Player one = Bukkit.getPlayerExact(args[2]);
            if (one == null) {
                o.fail(Errors.NEEDS_ONLINE_PLAYER, "Not online: " + args[2]);
                return;
            }
            players.add(one);
        } else {
            players.addAll(Bukkit.getOnlinePlayers());
        }
        boolean loaded = world.isChunkLoaded(p.x >> 4, p.z >> 4);
        o.put("chunkLoaded", loaded);
        o.put("entities", plugin.renderer().liveCount(p.id));
        if (loaded) o.put("groundSafe", plugin.safeSpots().terrainOk(world, p.x, p.y, p.z, p.facing, look));
        o.info("chunk " + (loaded ? "loaded" : "not loaded") + ", entities " + plugin.renderer().liveCount(p.id)
            + (loaded ? ", ground " + (plugin.safeSpots().terrainOk(world, p.x, p.y, p.z, p.facing, look) ? "safe" : "NOT SAFE") : ""));
        List<Map<String, Object>> who = new ArrayList<>();
        for (Player player : players) {
            boolean sameWorld = player.getWorld().equals(world);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", player.getName());
            m.put("uuid", player.getUniqueId().toString());
            m.put("access", plugin.hasAccess(player, p.destination));
            m.put("canEnter", plugin.canEnter(player, p));
            m.put("blockedBy", plugin.whyNot(player, p));
            m.put("seesIt", sameWorld ? plugin.renderer().seenBy(player, p.id) : null);
            who.add(m);
            o.info(player.getName() + ": access " + plugin.hasAccess(player, p.destination)
                + ", can enter " + plugin.canEnter(player, p) + " (blocked by " + plugin.whyNot(player, p) + ")"
                + ", sees it " + (sameWorld ? plugin.renderer().seenBy(player, p.id) : "(other world)"));
        }
        o.put("players", who);
        o.ok();
    }

    private void addWorld(Out o, String[] args) {
        if (args.length < 2) {
            o.fail(Errors.BAD_ARGUMENT, "Usage: /gate addworld <world> [label...]");
            return;
        }
        String world = args[1];
        if (Bukkit.getWorld(world) == null) {
            o.fail(Errors.NOT_FOUND, "No such loaded world: " + world);
            return;
        }
        String label = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : null;
        try {
            plugin.addWorldEntry(world, label);
        } catch (IOException | RuntimeException e) {
            o.fail(Errors.INTERNAL, "Couldn't add it to config.yml: " + e.getMessage());
            return;
        }
        plugin.store().clearGenerated(world);
        o.info("Adding " + world + " to the " + plugin.station().groupOf(world) + " platform...");
        plugin.station().generate(false).thenAccept(problems -> report(o, problems));
    }

    /** Rolls a new station: a new floor plan, new gates, new themes. */
    private void reseed(Out o, String[] args) {
        long seed = new java.util.Random().nextLong();
        if (args.length > 2) {
            o.fail(Errors.BAD_ARGUMENT, "Usage: /gate reseed [number]");
            return;
        }
        if (args.length > 1) {
            try {
                seed = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                o.fail(Errors.BAD_ARGUMENT, "The seed must be a whole number.");
                return;
            }
        }
        plugin.store().setSeed(seed == 0 ? 1 : seed);
        plugin.store().setGrowth(0);
        relayout(o);
    }

    /** Keeps this station's plan and designs, and spreads them out. */
    private void enlarge(Out o) {
        if (!plugin.settings().stationSlots.isEmpty()) {
            o.fail("hand-placed", "This station's gates are placed by hand under station.slots. Move those instead.");
            return;
        }
        plugin.store().setGrowth(plugin.store().growth() + 1);
        o.info("Enlarging the station, step " + plugin.store().growth() + "...");
        relayout(o);
    }

    private void relayout(Out o) {
        plugin.station().generate(true).thenAccept(problems -> report(o, problems));
    }

    /** The end of a slow command: "Done." or each problem. Over --json, the problems come back as a list. */
    private void report(Out o, List<String> problems) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> report(o, problems));
            return;
        }
        o.put("seed", plugin.station().seed());
        o.put("problems", problems);
        if (problems.isEmpty()) o.info("Done.");
        problems.forEach(o::warn);
        o.ok();
    }

    private void commit(Out o, String done, Gate changed) {
        boolean saved = plugin.saveStore();
        plugin.afterGateChange();
        if (!saved) {
            o.fail(Errors.INTERNAL, "Couldn't save gates.yml. See the console.");
            return;
        }
        if (changed != null) o.put("gate", asMap(changed));
        o.info(done);
        o.ok();
    }

    /** Gate ids are single tokens; over --json they're also kept to what every reader can match. */
    private static boolean validId(Out o, String id) {
        if (id.matches("[A-Za-z0-9_.-]{1,64}")) return true;
        o.fail(Errors.BAD_ARGUMENT, "A gate id is 1 to 64 letters, digits, _ . or -.");
        return false;
    }

    private Gate gate(Out o, String[] args, int index) {
        if (args.length <= index) {
            o.fail(Errors.BAD_ARGUMENT, "Which gate? Try /gate list");
            return null;
        }
        Gate p = plugin.store().get(args[index]);
        if (p == null) o.fail(Errors.NOT_FOUND, "No gate called " + args[index]);
        return p;
    }

    /** Coordinates from the arguments, or the player's own feet when none are given. */
    private Location position(Out o, String[] args, int from) {
        if (args.length <= from) {
            if (o.sender() instanceof Player player) return player.getLocation();
            o.fail(Errors.BAD_ARGUMENT, "From the console, give: <world> <x> <y> <z>");
            return null;
        }
        if (args.length < from + 4) {
            o.fail(Errors.BAD_ARGUMENT, "Give all of: <world> <x> <y> <z>");
            return null;
        }
        World world = Bukkit.getWorld(args[from]);
        if (world == null) {
            o.fail(Errors.NOT_FOUND, "No such loaded world: " + args[from]);
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(args[from + 1]), Integer.parseInt(args[from + 2]), Integer.parseInt(args[from + 3]));
        } catch (NumberFormatException e) {
            o.fail(Errors.BAD_ARGUMENT, "Coordinates must be whole numbers.");
            return null;
        }
    }

    private static BlockFace facingOf(Location at) {
        return switch (at.getBlockX() == 0 && at.getYaw() == 0 ? BlockFace.SOUTH : at.getDirection().getX() * at.getDirection().getX() > at.getDirection().getZ() * at.getDirection().getZ()
            ? (at.getDirection().getX() > 0 ? BlockFace.EAST : BlockFace.WEST)
            : (at.getDirection().getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH)) {
            case EAST -> BlockFace.WEST;   // face back at whoever is placing it
            case WEST -> BlockFace.EAST;
            case SOUTH -> BlockFace.NORTH;
            default -> BlockFace.SOUTH;
        };
    }

    private BlockFace parseFacing(Out o, String name) {
        try {
            return Settings.facing(name);
        } catch (IllegalArgumentException e) {
            o.fail(Errors.BAD_ARGUMENT, "Facing must be north, south, east or west.");
            return null;
        }
    }

    /** Where a command's output goes: chat lines for people, or one answer line for --json. */
    abstract static class Out {
        private final CommandSender sender;
        Out(CommandSender sender) { this.sender = sender; }
        CommandSender sender() { return sender; }
        abstract void info(String line);
        abstract void warn(String line);
        abstract void put(String key, Object value);
        abstract void fail(String code, String message);
        abstract void ok();
    }

    static class PlainOut extends Out {
        PlainOut(CommandSender sender) { super(sender); }
        @Override void info(String line) { sender().sendMessage(Component.text(line, NamedTextColor.GRAY)); }
        @Override void warn(String line) { sender().sendMessage(Component.text(line, NamedTextColor.RED)); }
        @Override void put(String key, Object value) {}
        @Override void fail(String code, String message) { warn(message); }
        @Override void ok() {}
    }

    /** Collects fields and warnings and sends exactly one answer, the first of ok() or fail(). */
    static final class JsonOut extends Out {
        final String id;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private boolean answered;

        JsonOut(CommandSender sender, String id) { super(sender); this.id = id; }
        @Override void info(String line) {}
        @Override void warn(String line) { warnings.add(line); }
        @Override void put(String key, Object value) { fields.put(key, value); }
        @Override void fail(String code, String message) { answer(Answer.error(id, code, message)); }
        @Override void ok() {
            if (!warnings.isEmpty()) fields.put("warnings", List.copyOf(warnings));
            answer(Answer.ok(id, fields));
        }
        void answer(String line) {
            if (answered) return;
            answered = true;
            Answer.send(sender()::sendMessage, line);
        }
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) args = new String[] {""};
        Stream<String> options = Stream.of();
        if (args.length == 1) {
            options = SUBCOMMANDS.stream().filter(sub -> allowed(sender, sub));
        } else if (args.length == 2 && Stream.of("move", "link", "remove", "test").anyMatch(args[0]::equalsIgnoreCase)) {
            options = plugin.store().all().stream().map(p -> p.id);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("link")) {
            options = plugin.store().all().stream().map(p -> p.id);
        } else if (args.length == 2 && Stream.of("addworld", "list").anyMatch(args[0]::equalsIgnoreCase)) {
            options = Bukkit.getWorlds().stream().map(World::getName);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            options = Bukkit.getWorlds().stream().map(World::getName);
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
