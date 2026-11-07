package com.extantape.waystation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /gate: create, move, link, list, remove, test, addworld, relayout, reload. Works from the console. */
final class AdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS =
        List.of("help", "list", "create", "fargate", "move", "link", "remove", "test", "addworld", "relayout", "reseed", "enlarge", "reload");

    private final WaystationPlugin plugin;

    AdminCommand(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // waystation.admin grants every waystation.admin.<subcommand>. Grant them one by one for a narrower role.
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) sub = "help";
        if (!allowed(sender, sub)) {
            error(sender, "You don't have permission for that.");
            return true;
        }
        switch (sub) {
            case "list" -> list(sender, args);
            case "create" -> create(sender, args);
            case "fargate" -> fargate(sender, args);
            case "move" -> move(sender, args);
            case "link" -> link(sender, args);
            case "remove" -> remove(sender, args);
            case "test" -> test(sender, args);
            case "addworld" -> addWorld(sender, args);
            case "relayout" -> relayout(sender);
            case "reseed" -> reseed(sender, args);
            case "enlarge" -> enlarge(sender);
            case "reload" -> sender.sendMessage(text(plugin.reload(), NamedTextColor.YELLOW));
            default -> help(sender);
        }
        return true;
    }

    /** These nodes default to false, which would shut the console out too, so it's let in by name. */
    private static boolean allowed(CommandSender sender, String sub) {
        return !(sender instanceof Player) || sender.hasPermission("waystation.admin") || sender.hasPermission("waystation.admin." + sub);
    }

    private void help(CommandSender s) {
        for (String line : List.of(
            "/gate list [world]",
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
            "/gate reload")) {
            sender(s, line);
        }
    }

    private void list(CommandSender s, String[] args) {
        String only = args.length > 1 ? args[1] : null;
        sender(s, "Station seed " + plugin.station().seed() + ", plan " + plugin.station().planName() + ", enlarged " + plugin.store().growth());
        if (!plugin.station().blockedSlots().isEmpty()) error(s, "No safe floor for: " + String.join(", ", plugin.station().blockedSlots()) + ". /gate enlarge or /gate reseed");
        for (Station.Platform platform : plugin.station().platforms()) {
            sender(s, "Platform " + platform.number() + " (" + platform.group() + "): "
                + String.join(", ", platform.gates().stream().map(p -> p.id).toList()));
        }
        for (Gate p : plugin.store().all()) {
            if (only != null && !only.equals(p.world)) continue;
            sender(s, p.id + ": " + p.world + " " + p.x + " " + p.y + " " + p.z + " " + p.facing.name().toLowerCase(Locale.ROOT)
                + " -> " + p.destination + (plugin.isDead(p) ? " (DEAD)" : plugin.isActive(p) ? "" : " (closed)") + (p.link != null ? " link " + p.link : ""));
        }
    }

    private void create(CommandSender s, String[] args) {
        if (args.length < 3) {
            error(s, "Usage: /gate create <id> <destination world> [<world> <x> <y> <z> <facing>]");
            return;
        }
        String id = args[1], destination = args[2];
        if (plugin.store().get(id) != null) {
            error(s, "There's already a gate called " + id);
            return;
        }
        if (Bukkit.getWorld(destination) == null) {
            error(s, "No such loaded world: " + destination);
            return;
        }
        Location at = position(s, args, 3);
        if (at == null) return;
        BlockFace facing = args.length > 7 ? parseFacing(s, args[7]) : facingOf(at);
        if (facing == null) return;
        plugin.store().put(new Gate(id, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), facing, destination));
        commit(s, "Created " + id);
    }

    /** A gate to another server. Destinations are defined under fargate-destinations in config.yml. */
    private void fargate(CommandSender s, String[] args) {
        if (args.length < 3) {
            error(s, "Usage: /gate fargate <id> <destination> [<world> <x> <y> <z> <facing>]");
            return;
        }
        if (plugin.store().get(args[1]) != null) {
            error(s, "There's already a gate called " + args[1]);
            return;
        }
        if (!plugin.settings().fargates.containsKey(args[2])) {
            error(s, "No such destination under fargate-destinations: " + args[2]);
            return;
        }
        Location at = position(s, args, 3);
        if (at == null) return;
        BlockFace facing = args.length > 7 ? parseFacing(s, args[7]) : facingOf(at);
        if (facing == null) return;
        plugin.store().put(new Gate(args[1], at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), facing, Gate.FARGATE_PREFIX + args[2]));
        commit(s, "Created fargate " + args[1]);
        if (!Bukkit.getServer().isAcceptingTransfers()) {
            error(s, "It stays hidden until accepts-transfers=true is set in server.properties.");
        }
    }

    private void move(CommandSender s, String[] args) {
        Gate p = gate(s, args, 1);
        if (p == null) return;
        Location at = position(s, args, 2);
        if (at == null) return;
        BlockFace facing = args.length > 6 ? parseFacing(s, args[6]) : args.length > 2 ? p.facing : facingOf(at);
        if (facing == null) return;
        p.world = at.getWorld().getName();
        p.x = at.getBlockX();
        p.y = at.getBlockY();
        p.z = at.getBlockZ();
        p.facing = facing;
        p.restUntil = 0; // an admin's move also ends any rest after a tribute
        commit(s, "Moved " + p.id);
    }

    private void link(CommandSender s, String[] args) {
        Gate a = gate(s, args, 1), b = a == null ? null : gate(s, args, 2);
        if (b == null) return;
        a.destination = b.world;
        a.link = b.id;
        b.destination = a.world;
        b.link = a.id;
        commit(s, "Linked " + a.id + " (to " + a.destination + ") and " + b.id + " (to " + b.destination + ")");
    }

    private void remove(CommandSender s, String[] args) {
        Gate p = gate(s, args, 1);
        if (p == null) return;
        plugin.store().remove(p.id);
        for (Gate other : plugin.store().all()) if (p.id.equals(other.link)) other.link = null;
        if (p.isFargate()) {
            commit(s, "Removed " + p.id + ".");
        } else if (plugin.isDead(p)) {
            plugin.store().clearGenerated(p.destination); // the world is gone: if it ever returns, it gets a new pair
            commit(s, "Removed " + p.id + ".");
        } else {
            commit(s, "Removed " + p.id + ". It stays gone until /gate addworld " + p.destination + " brings it back.");
        }
        plugin.station().sync(false);
    }

    /** Reports what a gate is doing, and what it means for one player or for everyone online. */
    private void test(CommandSender s, String[] args) {
        Gate p = gate(s, args, 1);
        if (p == null) return;
        World world = Bukkit.getWorld(p.world);
        Settings.Look look = plugin.settings().look(plugin.lookName(p));
        sender(s, p.id + " at " + p.world + " " + p.x + " " + p.y + " " + p.z + " facing " + p.facing.name().toLowerCase(Locale.ROOT));
        sender(s, "destination " + p.destination + ", " + (plugin.isDead(p) ? "DEAD" : plugin.isActive(p) ? "open" : "CLOSED") + ", platform "
            + (plugin.station().platformOf(p) == null ? "none" : plugin.station().platformOf(p).group()));
        if (world == null) {
            error(s, "world isn't loaded");
            return;
        }
        boolean loaded = world.isChunkLoaded(p.x >> 4, p.z >> 4);
        sender(s, "chunk " + (loaded ? "loaded" : "not loaded") + ", entities " + plugin.renderer().liveCount(p.id)
            + (loaded ? ", ground " + (plugin.safeSpots().terrainOk(world, p.x, p.y, p.z, p.facing, look) ? "safe" : "NOT SAFE") : ""));
        List<Player> players = new ArrayList<>();
        if (args.length > 2) {
            Player one = Bukkit.getPlayerExact(args[2]);
            if (one == null) {
                error(s, "Not online: " + args[2]);
                return;
            }
            players.add(one);
        } else {
            players.addAll(Bukkit.getOnlinePlayers());
        }
        for (Player player : players) {
            sender(s, player.getName() + ": access " + plugin.hasAccess(player, p.destination)
                + ", can enter " + plugin.canEnter(player, p) + " (blocked by " + plugin.whyNot(player, p) + ")"
                + ", sees it " + (player.getWorld().equals(world) ? plugin.renderer().seenBy(player, p.id) : "(other world)"));
        }
    }

    private void addWorld(CommandSender s, String[] args) {
        if (args.length < 2) {
            error(s, "Usage: /gate addworld <world> [label...]");
            return;
        }
        String world = args[1];
        if (Bukkit.getWorld(world) == null) {
            error(s, "No such loaded world: " + world);
            return;
        }
        String label = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : null;
        try {
            plugin.addWorldEntry(world, label);
        } catch (IOException | RuntimeException e) {
            error(s, "Couldn't add it to config.yml: " + e.getMessage());
            return;
        }
        plugin.store().clearGenerated(world);
        sender(s, "Adding " + world + " to the " + plugin.station().groupOf(world) + " platform...");
        plugin.station().generate(false).thenAccept(problems -> report(s, problems));
    }

    /** Rolls a new station: a new floor plan, new gates, new themes. */
    private void reseed(CommandSender s, String[] args) {
        long seed = new java.util.Random().nextLong();
        if (args.length > 1) {
            try {
                seed = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                error(s, "The seed must be a whole number.");
                return;
            }
        }
        plugin.store().setSeed(seed == 0 ? 1 : seed);
        plugin.store().setGrowth(0);
        relayout(s);
    }

    /** Keeps this station's plan and designs, and spreads them out. */
    private void enlarge(CommandSender s) {
        if (!plugin.settings().stationSlots.isEmpty()) {
            error(s, "This station's gates are placed by hand under station.slots. Move those instead.");
            return;
        }
        plugin.store().setGrowth(plugin.store().growth() + 1);
        sender(s, "Enlarging the station, step " + plugin.store().growth() + "...");
        relayout(s);
    }

    private void relayout(CommandSender s) {
        plugin.station().generate(true).thenAccept(problems -> report(s, problems));
    }

    private void report(CommandSender s, List<String> problems) {
        if (problems.isEmpty()) sender(s, "Done.");
        problems.forEach(problem -> error(s, problem));
    }

    private void commit(CommandSender s, String done) {
        if (plugin.saveStore()) sender(s, done);
        else error(s, "Couldn't save gates.yml. See the console.");
        plugin.afterGateChange();
    }

    private Gate gate(CommandSender s, String[] args, int index) {
        if (args.length <= index) {
            error(s, "Which gate? Try /gate list");
            return null;
        }
        Gate p = plugin.store().get(args[index]);
        if (p == null) error(s, "No gate called " + args[index]);
        return p;
    }

    /** Coordinates from the arguments, or the player's own feet when none are given. */
    private Location position(CommandSender s, String[] args, int from) {
        if (args.length <= from) {
            if (s instanceof Player player) return player.getLocation();
            error(s, "From the console, give: <world> <x> <y> <z>");
            return null;
        }
        if (args.length < from + 4) {
            error(s, "Give all of: <world> <x> <y> <z>");
            return null;
        }
        World world = Bukkit.getWorld(args[from]);
        if (world == null) {
            error(s, "No such loaded world: " + args[from]);
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(args[from + 1]), Integer.parseInt(args[from + 2]), Integer.parseInt(args[from + 3]));
        } catch (NumberFormatException e) {
            error(s, "Coordinates must be whole numbers.");
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

    private BlockFace parseFacing(CommandSender s, String name) {
        try {
            return Settings.facing(name);
        } catch (IllegalArgumentException e) {
            error(s, "Facing must be north, south, east or west.");
            return null;
        }
    }

    private static Component text(String message, NamedTextColor color) {
        return Component.text(message, color);
    }

    private static void sender(CommandSender s, String message) {
        s.sendMessage(text(message, NamedTextColor.GRAY));
    }

    private static void error(CommandSender s, String message) {
        s.sendMessage(text(message, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
