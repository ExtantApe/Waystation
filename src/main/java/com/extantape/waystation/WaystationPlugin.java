package com.extantape.waystation;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires it all together, and answers the questions several classes share. */
public final class WaystationPlugin extends JavaPlugin {
    private Settings settings;
    private GateStore store;
    private PlayerData data;
    private Claims claims;
    private SafeSpots safeSpots;
    private Worlds worlds;
    private Station station;
    private Renderer renderer;
    private Protection protection;
    private Payments payments;
    private Mover mover;
    private String escapeName; // as registered at startup; null if it was off
    private List<String> escapeAliases = List.of();
    private boolean storeReadable = true;
    private Arrival arrival;

    @Override
    public void onEnable() {
        boolean firstStart = !new File(getDataFolder(), "gates.yml").exists();
        fillInStarterConfig();
        saveDefaultConfig();
        store = new GateStore(new File(getDataFolder(), "gates.yml"));
        data = new PlayerData(new File(getDataFolder(), "players.yml"), getLogger());
        worlds = Worlds.detect(this);
        claims = new Claims(getLogger());
        safeSpots = new SafeSpots(claims);
        payments = new Payments(this);
        station = new Station(this);
        renderer = new Renderer(this);
        arrival = new Arrival(this);

        try {
            settings = new Settings(getConfig());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "config.yml has a problem, so Waystation is staying off", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadStore();

        Travel travel = new Travel(this);
        mover = new Mover(this);
        getServer().getPluginManager().registerEvents(mover, this);
        getServer().getPluginManager().registerEvents(travel, this);
        getServer().getPluginManager().registerEvents(renderer, this);
        protection = new Protection(this);
        getServer().getPluginManager().registerEvents(protection, this);
        getServer().getPluginManager().registerEvents(station, this);
        getServer().getPluginManager().registerEvents(arrival, this);

        registerCommand("gate", "Create, move, link, list, remove and test gates.", new AdminCommand(this));
        registerCommand("waygate", "Shows the coordinates of the closest gate you can use.", List.of("nearestgate", "findgate"), playerCommand("waystation.find",
            "Only players can ask for the nearest gate.", (player, args) -> nearest(player)));
        registerCommand("buygate", "Buy access to a world's gate.", playerCommand("waystation.buy", "Only players can buy gates.", this::buy));
        registerEscapeCommand(travel);
        registerReturnCommand();
        this.travel = travel;
        getServer().getServicesManager().register(LandingService.class, travel::landing, this, org.bukkit.plugin.ServicePriority.Normal);
        markers = new IslandMarkers(this);
        markers.reload();
        getServer().getPluginManager().registerEvents(markers, this);

        if (settings.bluemapEnabled && getServer().getPluginManager().getPlugin("BlueMap") != null) {
            bluemap = new BlueMapMarkers(this); // only touched when BlueMap is here, since it needs BlueMap's classes
        }
        if (!settings.fargates.isEmpty() && !Bukkit.getServer().isAcceptingTransfers()) {
            getLogger().warning("Fargates stay hidden until accepts-transfers=true is set in server.properties, since players couldn't come back.");
        }
        if (!claims.active()) {
            getLogger().warning("GriefPrevention isn't installed: /move has no claims to check.");
        }

        // Worlds are all loaded by the time the first tick runs.
        Bukkit.getScheduler().runTask(this, () -> {
            if (settings.exampleWorlds) makeExampleWorlds(firstStart);
            station.ensureWorld();
            Bukkit.getWorlds().forEach(renderer::sweep);
            arrival.hookLatchkey();
            arrival.refresh();
            station.sync(true);
            Bukkit.getScheduler().runTaskTimer(this, renderer::pass, 20L, 20L);
            Bukkit.getScheduler().runTaskTimer(this, markers::pass, 20L, 20L);
            Bukkit.getScheduler().runTaskTimer(this, () -> station.sync(false), 600L, 600L);
        });
    }

    /** A config.yml holding only example-worlds becomes the full default config, with that setting kept. */
    private void fillInStarterConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        YamlConfiguration starter = YamlConfiguration.loadConfiguration(file);
        if (!starter.getKeys(false).equals(Set.of("example-worlds")) || !starter.getBoolean("example-worlds")) return;
        saveResource("config.yml", true);
        try {
            String text = Files.readString(file.toPath());
            Files.writeString(file.toPath(), text.replace("example-worlds: false", "example-worlds: true"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Couldn't turn on example-worlds in config.yml", e);
        }
        reloadConfig();
    }

    /** Only ever on the first start, and a world that already exists is left alone. */
    private void makeExampleWorlds(boolean firstStart) {
        if (!firstStart) {
            getLogger().warning("example-worlds only works before the first start, so no worlds were made.");
            return;
        }
        worlds.createExampleWorlds(List.of("example_survival", "example_creative"));
    }

    private Travel travel;
    private ReturnCountdown returns;
    private String returnName; // as registered at startup; null if it was off
    private List<String> returnAliases = List.of();
    private IslandMarkers markers;
    private BlueMapMarkers bluemap;

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (returns != null) returns.cancelAll();
        if (mover != null) mover.closeAll();
        if (markers != null) markers.removeAll();
        if (renderer != null) renderer.removeAll();
        if (bluemap != null) bluemap.close();
    }

    /** /buygate <world>: pay, then have the permission granted. If the grant doesn't take, pay it back. */
    private void buy(Player player, String[] args) {
        if (args.length == 0) {
            List<String> forSale = new ArrayList<>();
            for (Settings.WorldEntry entry : settings.worlds.values()) {
                if (isForSale(player, entry.name())) forSale.add(entry.name() + " (" + payments.text(entry.price()) + ")");
            }
            player.sendMessage(settings.message(forSale.isEmpty() ? "buy-none" : "buy-list", "list", String.join(", ", forSale)));
            return;
        }
        String world = args[0];
        Settings.WorldEntry entry = settings.worlds.get(world);
        if (entry == null || entry.price().free()) {
            player.sendMessage(settings.message("buy-not-for-sale", "world", world));
            return;
        }
        if (hasAccess(player, world)) {
            player.sendMessage(settings.message("buy-owned", "world", world));
            return;
        }
        if (!payments.canPay(player, entry.price())) {
            player.sendMessage(settings.message("buy-cost", "world", world, "cost", payments.text(entry.price())));
            return;
        }
        payments.pay(player, entry.price());
        for (String command : settings.buyCommands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("<player>", player.getName()).replace("<world>", world));
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            if (hasAccess(player, world)) {
                renderer.refresh(player);
                player.sendMessage(settings.message("buy-done", "world", world, "cost", payments.text(entry.price())));
            } else {
                payments.refund(player, entry.price());
                getLogger().warning("buy.grant-commands didn't give " + player.getName() + " access to " + world + ". Refunded.");
                player.sendMessage(settings.message("buy-failed", "world", world));
            }
        }, 40L);
    }

    /** Is this a world the player could buy their way into? Hooks that say no still say no. */
    boolean isForSale(Player player, String world) {
        Settings.WorldEntry entry = settings.worlds.get(world);
        return entry != null && !entry.price().free() && !hasAccess(player, world);
    }

    /** /waygate: the closest gate in this world that this player can actually use. */
    private void nearest(Player player) {
        Gate best = null;
        double bestDistance = Double.MAX_VALUE;
        org.bukkit.Location at = player.getLocation();
        for (Gate p : store.inWorld(at.getWorld().getName())) {
            if (p.isFargate() || !canEnter(player, p)) continue;
            double d = Math.hypot(p.x + 0.5 - at.getX(), p.z + 0.5 - at.getZ());
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        if (best == null) {
            player.sendMessage(settings.message(escapeAvailable() ? "nearest-none-escape" : "nearest-none", "command", "/" + settings.escapeCommand));
            return;
        }
        player.sendMessage(settings.message("nearest", "name", plainLabelOf(best),
            "x", String.valueOf(best.x), "y", String.valueOf(best.y), "z", String.valueOf(best.z),
            "distance", String.valueOf(Math.round(bestDistance)),
            "direction", compass(best.x + 0.5 - at.getX(), best.z + 0.5 - at.getZ())));
    }

    private static String compass(double dx, double dz) {
        String[] names = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        return names[(int) Math.floorMod(Math.round(angle / 45), 8L)];
    }

    private void loadStore() {
        try {
            store.load();
            storeReadable = true;
            station.loadGroups();
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            // Never overwrite a file we couldn't read: it may be hand-fixable.
            storeReadable = false;
            getLogger().log(Level.SEVERE, "gates.yml can't be read. Nothing will be saved over it until it's fixed", e);
        }
    }

    /** /gate reload. A bad file leaves everything running as it was. */
    String reload() {
        File file = new File(getDataFolder(), "config.yml");
        Settings fresh;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(file);
            y.setDefaults(getConfig().getDefaults()); // the jar's config.yml, for messages an older file doesn't have
            fresh = new Settings(y);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return "config.yml has a problem, so nothing changed: " + e.getMessage();
        }
        reloadConfig();
        settings = fresh;
        loadStore();
        if (!storeReadable) return "gates.yml has a problem. Settings reloaded, but the gates in memory stay as they were.";
        checkEscapeCommand();
        checkReturnCommand();
        markers.reload();
        arrival.refresh();
        station.sync(true);
        return "Waystation reloaded.";
    }

    /** A command only players can use, with a permission node that also hides it from those without. */
    private static BasicCommand playerCommand(String permission, String notPlayer, java.util.function.BiConsumer<Player, String[]> run) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (source.getSender() instanceof Player player) run.accept(player, args);
                else source.getSender().sendMessage(notPlayer);
            }

            /**
             * Players need the permission, and don't see the command without it. Anyone else, like the console, may
             * run it, to be told it's for players: permissions that default to false would hide it from the console too.
             */
            @Override
            public boolean canUse(CommandSender sender) {
                return !(sender instanceof Player) || sender.hasPermission(permission);
            }
        };
    }

    /**
     * The escape command, under its configured name. Paper registers commands once, at startup, so a new name or
     * turning it on applies after a restart. Turning it off applies at once: it then refuses.
     */
    private void registerEscapeCommand(Travel travel) {
        if (!escapeAvailable()) return;
        escapeName = settings.escapeCommand;
        escapeAliases = List.copyOf(settings.escapeAliases);
        registerCommand(escapeName, "Go to the station", escapeAliases, new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                CommandSender sender = source.getSender();
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this. From the console: mvtp <player> w:" + settings.stationWorld);
                } else if (!settings.escapeEnabled) {
                    player.sendMessage(settings.message("escape-off", "command", "/" + escapeName));
                } else if (player.hasPermission(settings.escapePermission)) {
                    travel.escape(player);
                } else {
                    player.sendMessage(settings.message("escape-no-permission", "command", "/" + escapeName));
                }
            }
        });
    }

    /** After a reload: say so if the escape command's name or on/off state needs a restart. */
    private void checkEscapeCommand() {
        boolean wanted = escapeAvailable();
        if (wanted && escapeName == null) {
            getLogger().warning("escape.enabled is now true. /" + settings.escapeCommand + " appears after a restart.");
        } else if (wanted && (!escapeName.equals(settings.escapeCommand) || !escapeAliases.equals(settings.escapeAliases))) {
            getLogger().warning("The escape command's new name or aliases apply after a restart. Until then it's still /" + escapeName + ".");
        }
    }

    /**
     * The return command, under its configured name. Like the escape command, a new name or turning it on applies after
     * a restart. Its destination and countdown are read when each countdown starts, so a reload changes those at once.
     */
    private void registerReturnCommand() {
        Settings.Return config = settings.returnCommand;
        if (!config.enabled()) return;
        returns = new ReturnCountdown(this);
        getServer().getPluginManager().registerEvents(returns, this);
        returnName = config.command();
        returnAliases = config.aliases();
        String permission = config.permission();
        registerCommand(returnName, "Go to another server after a short countdown", returnAliases, playerCommand(permission,
            "Only players can use this.", (player, args) -> returns.request(player)));
        String problem = config.problem(settings.fargates);
        if (problem != null) getLogger().warning("The return command isn't ready: " + problem + ". Players are told it isn't set up.");
    }

    /** After a reload: say so if the return command's name or on/off state needs a restart. */
    private void checkReturnCommand() {
        Settings.Return config = settings.returnCommand;
        if (config.enabled() && returnName == null) {
            getLogger().warning("return.enabled is now true. /" + config.command() + " appears after a restart.");
        } else if (!config.enabled() && returnName != null) {
            getLogger().warning("return.enabled is now false. /" + returnName + " says it isn't set up until a restart removes it.");
        } else if (returnName != null && (!returnName.equals(config.command()) || !returnAliases.equals(config.aliases()))) {
            getLogger().warning("The return command's new name or aliases apply after a restart. Until then it's still /" + returnName + ".");
        }
        String problem = config.enabled() ? config.problem(settings.fargates) : null;
        if (problem != null) getLogger().warning("The return command isn't ready: " + problem + ".");
    }

    /** Adds a world to config.yml, keeping the rest of the file and its comments. */
    void addWorldEntry(String world, String label) throws IOException {
        getConfig().set("worlds." + world + ".enabled", true);
        if (label != null) getConfig().set("worlds." + world + ".label", label);
        getConfig().save(new File(getDataFolder(), "config.yml"));
        settings = new Settings(getConfig());
    }

    /** Call after anything changes which gates exist or where they are. */
    void afterGateChange() {
        station.buildPlatforms();
        protection.rebuild();
        renderer.rebuild();
        if (bluemap != null) bluemap.update();
    }

    boolean saveStore() {
        if (!storeReadable) {
            getLogger().severe("Not saving gates.yml: it couldn't be read, and saving would overwrite it.");
            return false;
        }
        try {
            store.save();
            return true;
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Couldn't save gates.yml", e);
            return false;
        }
    }

    boolean isSurvivalWorld(World world) {
        return worlds.isSurvival(world);
    }

    /** Why this player can or can't use this gate, in words, for /gate test. */
    String whyNot(Player player, Gate p) {
        if (!isActive(p)) return isDead(p) ? "the world it leads to is gone" : "the gate is closed";
        if (p.isFargate()) {
            Settings.FargateTarget target = settings.fargates.get(p.fargateName());
            if (!player.hasPermission(target.permission())) return "lacks " + target.permission();
            if (!target.protocols().contains(clientProtocol(player))) return "client version " + clientProtocol(player) + " isn't in protocols";
        } else if (!hasAccess(player, p.destination)) {
            return "lacks multiverse.access." + p.destination;
        }
        return canEnter(player, p) ? "nothing: allowed" : "a waystation.* node set to false, or another plugin";
    }

    /** A dead gate leads to a world that's been deleted or unloaded. It stays until an admin removes it. */
    boolean isDead(Gate p) {
        return !p.isFargate() && Bukkit.getWorld(p.destination) == null;
    }

    /** Dead gates show to whoever could have used them, and to admins so they can find them. */
    boolean canSee(Player player, Gate p) {
        if (isDead(p)) return hasAccess(player, p.destination) || player.hasPermission("waystation.admin") || player.hasPermission("waystation.admin.seedead");
        if (isActive(p) && isForSale(player, p.destination) && allowedByHooks(player, p)) return true; // shown locked
        return canEnter(player, p);
    }

    /** Is this gate switched on, with both ends loaded and the destination open? */
    boolean isActive(Gate p) {
        // A player can't come back to a server that refuses transfers, so its fargates stay shut.
        if (p.isFargate()) {
            return p.enabled && Bukkit.getWorld(p.world) != null && settings.fargates.containsKey(p.fargateName()) && Bukkit.getServer().isAcceptingTransfers();
        }
        return p.enabled && settings.worldOpen(p.destination) && Bukkit.getWorld(p.world) != null && Bukkit.getWorld(p.destination) != null;
    }

    /** Access is Multiverse's own permission, read through Bukkit so LuckPerms contexts apply. */
    boolean hasAccess(Player player, String world) {
        return player.hasPermission("multiverse.access." + world);
    }

    /** The one rule for both seeing and using a gate. */
    boolean canEnter(Player player, Gate p) {
        if (p.isFargate()) {
            if (!isActive(p)) return false;
            Settings.FargateTarget target = settings.fargates.get(p.fargateName());
            return player.hasPermission(target.permission()) && target.protocols().contains(clientProtocol(player)) && allowedByHooks(player, p);
        }
        return isActive(p) && hasAccess(player, p.destination) && allowedByHooks(player, p);
    }

    /**
     * Fine-tuning on top of Multiverse access, which it can narrow but never widen. Each node is off
     * only when explicitly set to false, so nothing changes until someone sets one in LuckPerms, for
     * a player or a role, with contexts if they like. Other plugins can say no too, through the event.
     */
    private boolean allowedByHooks(Player player, Gate p) {
        String group = station.groupOf(p.destination);
        if (explicitlyDenied(player, "waystation.use." + p.destination)
            || explicitlyDenied(player, "waystation.platform." + group)
            || explicitlyDenied(player, "waystation.gate." + p.id)) {
            return false;
        }
        if (WaystationAccessEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        WaystationAccessEvent event = new WaystationAccessEvent(player, p.id, p.world, p.destination, group);
        getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /**
     * The player's real client version. ViaVersion lets other versions join, so it's the one to ask,
     * by reflection since it's optional. With no ViaVersion, every client matches the server. A failed
     * answer reads as -1, which matches no destination, so an unknown client gets no fargate.
     */
    int clientProtocol(Player player) {
        org.bukkit.plugin.Plugin via = getServer().getPluginManager().getPlugin("ViaVersion");
        if (via == null) return serverProtocol();
        try {
            Object api = Class.forName("com.viaversion.viaversion.api.Via", true, via.getClass().getClassLoader()).getMethod("getAPI").invoke(null);
            Class<?> type = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, via.getClass().getClassLoader());
            return (int) type.getMethod("getPlayerVersion", java.util.UUID.class).invoke(api, player.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Couldn't ask ViaVersion for a client version: " + e);
            return -1;
        }
    }

    /** Paper 26.1 has no other way to read the server's protocol number. Deprecated since 1.7.2, not marked for removal. */
    @SuppressWarnings("deprecation")
    private static int serverProtocol() {
        return Bukkit.getUnsafe().getProtocolVersion();
    }

    boolean explicitlyDenied(Player player, String node) {
        return player.isPermissionSet(node) && !player.hasPermission(node);
    }

    /** A gate's own look, or its destination's, or its own world's for a return gate. */
    String lookName(Gate p) {
        if (p.look != null) return p.look;
        if (p.isFargate()) return "fargate";
        Settings.WorldEntry there = settings.worlds.get(p.destination);
        if (there != null && there.look() != null) return there.look();
        Settings.WorldEntry here = settings.worlds.get(p.world);
        return here == null ? null : here.look();
    }

    String labelOf(Gate p) {
        if (isDead(p)) return settings.deadLabel.replace("<name>", station.plainName(p.destination));
        if (p.isFargate()) {
            Settings.FargateTarget target = settings.fargates.get(p.fargateName());
            return settings.fargateLabel.replace("<name>", target == null ? p.fargateName() : target.label());
        }
        if (p.label != null) return p.label;
        if (p.destination.equals(settings.stationWorld)) return settings.returnLabel;
        return station.labelOf(p.destination);
    }

    String plainLabelOf(Gate p) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(labelOf(p)));
    }

    Location spawnOf(World world) {
        return worlds.spawnOf(world);
    }

    Worlds worlds() { return worlds; }

    Arrival arrival() { return arrival; }

    /** The escape command goes to the station, so it needs one, and so Multiverse-Core. */
    boolean escapeAvailable() {
        return settings.escapeEnabled && worlds.managesWorlds();
    }

    Travel travel() { return travel; }
    Settings settings() { return settings; }
    GateStore store() { return store; }
    PlayerData data() { return data; }
    SafeSpots safeSpots() { return safeSpots; }
    Payments payments() { return payments; }
    Station station() { return station; }
    Renderer renderer() { return renderer; }
}
