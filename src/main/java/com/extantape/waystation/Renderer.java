package com.extantape.waystation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws everything with display entities and decides who sees what.
 *
 * Entities are never saved (setPersistent false), so a shutdown, even a crash, can't leave any behind.
 * That also means they vanish when their chunk unloads, and /kill can remove them, so a once-a-second
 * pass puts back whatever is missing or displaced. The same pass re-checks who may see each scene,
 * which is what catches LuckPerms changes without any LuckPerms hook.
 *
 * Entities start invisible to everyone and are shown player by player, so a new one can't flash up
 * for someone who shouldn't see it.
 */
final class Renderer implements Listener {
    static final String TAG = "waystation";
    static final String CHEST_TAG = "waystation-chest";
    /** Frames never use these, wherever a world's palette says: they'd read as a nether or End portal. */
    private static final Set<String> NEVER = Set.of("obsidian", "crying_obsidian", "end_stone", "end_stone_bricks", "purpur_block", "purpur_pillar", "bedrock");
    private static final List<String> GLOWS = List.of("sea_lantern", "glowstone", "shroomlight", "ochre_froglight", "pearlescent_froglight", "verdant_froglight");

    /** A group of entities that share one anchor, and so one chunk. */
    private record Scene(String key, Location anchor, Predicate<Player> seenBy, Consumer<List<Entity>> spawn) {}

    private final WaystationPlugin plugin;
    private final Map<String, Scene> scenes = new HashMap<>();
    private final Map<String, List<Entity>> live = new HashMap<>();

    Renderer(WaystationPlugin plugin) {
        this.plugin = plugin;
    }

    /** Recomputes what should exist from the current settings and gates, and redraws it. */
    void rebuild() {
        removeAll();
        scenes.clear();
        Settings settings = plugin.settings();
        for (Gate p : plugin.store().all()) {
            World world = Bukkit.getWorld(p.world);
            if (world == null) {
                plugin.getLogger().warning("Gate " + p.id + " is in a world that isn't loaded: " + p.world);
                continue;
            }
            Station.Platform platform = plugin.station().platformOf(p);
            Settings.WorldEntry entry = settings.worlds.get(p.destination);
            if (entry != null && !entry.price().free()) {
                Location tag = new Location(world, p.x + 0.5, p.y + 1.5, p.z + 0.5);
                scenes.put("price:" + p.id, new Scene("price:" + p.id, tag, player -> plugin.isForSale(player, p.destination) && plugin.canSee(player, p),
                    list -> list.add(spawnText(tag, Labels.price(plugin.payments().text(entry.price()), p.destination), 1f, Display.Billboard.CENTER))));
            }
            Location anchor = new Location(world, p.x + 0.5, p.y, p.z + 0.5);
            scenes.put("gate:" + p.id, new Scene("gate:" + p.id, anchor, player -> plugin.canSee(player, p), list -> spawnGate(p, platform, list)));
            if (platform != null) {
                scenes.put("floor:" + p.id, new Scene("floor:" + p.id, anchor, player -> plugin.canSee(player, p), list -> spawnFloor(p, platform, list)));
            } else if (!p.isFargate() && settings.moveEnabled && settings.moveWorlds.contains(p.world)) {
                Location chest = chestSpot(world, p, settings.look(plugin.lookName(p)));
                scenes.put("chest:" + p.id, new Scene("chest:" + p.id, chest, player -> plugin.canEnter(player, p), list -> spawnChest(chest, p, list)));
            }
        }
        World station = Bukkit.getWorld(settings.stationWorld);
        if (station != null) {
            for (Station.Platform platform : plugin.station().platforms()) {
                Gate middle = platform.gates().get(platform.gates().size() / 2);
                List<Gate> gates = platform.gates();
                scenes.put("sign:" + platform.group(), new Scene("sign:" + platform.group(),
                    new Location(station, middle.x + 0.5, middle.y, middle.z + 0.5),
                    player -> gates.stream().anyMatch(g -> plugin.canSee(player, g)),
                    list -> spawnSign(platform, middle, list)));
            }
            if (!plugin.station().platforms().isEmpty()) {
                int[] center = plugin.station().center(station);
                Location at = new Location(station, center[0] + 0.5, settings.stationY, center[1] + 0.5);
                scenes.put("station", new Scene("station", at, player -> true, list -> spawnMonument(at, list)));
            }
        }
        pass();
    }

    /** The once-a-second pass. */
    void pass() {
        for (Scene scene : scenes.values()) ensure(scene);
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
    }

    private void ensure(Scene scene) {
        Location at = scene.anchor();
        World world = at.getWorld();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz) || !world.getChunkAt(cx, cz).isEntitiesLoaded()) {
            live.remove(scene.key());   // they went with the chunk, and will be redrawn when it's back
            return;
        }
        List<Entity> entities = live.get(scene.key());
        if (entities != null) {
            boolean intact = true;
            for (Entity e : entities) {
                intact &= e.isValid() && e.getWorld().equals(world) && e.getLocation().distanceSquared(at) < 400;
            }
            if (intact) return;
            entities.forEach(Entity::remove);
        }
        List<Entity> fresh = new ArrayList<>();
        scene.spawn().accept(fresh);
        live.put(scene.key(), fresh);
    }

    /** Applies visibility for one player, for the scenes in the world they're in. */
    void refresh(Player player) {
        String world = player.getWorld().getName();
        for (Scene scene : scenes.values()) {
            if (!scene.anchor().getWorld().getName().equals(world)) continue;
            List<Entity> entities = live.get(scene.key());
            if (entities == null) continue;
            boolean visible = scene.seenBy().test(player);
            for (Entity e : entities) {
                if (player.canSee(e) == visible) continue;
                if (visible) player.showEntity(plugin, e);
                else player.hideEntity(plugin, e);
            }
        }
    }

    void removeAll() {
        live.values().forEach(list -> list.forEach(Entity::remove));
        live.clear();
    }

    /** Removes tagged entities we don't own. There shouldn't be any, but a stray must never linger. */
    void sweep(World world) {
        List<Entity> owned = live.values().stream().flatMap(List::stream).toList();
        for (Entity e : world.getEntities()) {
            if (e.getScoreboardTags().contains(TAG) && !owned.contains(e)) e.remove();
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Entity> owned = live.values().stream().flatMap(List::stream).toList();
        for (Entity e : event.getEntities()) {
            if (e.getScoreboardTags().contains(TAG) && !owned.contains(e)) e.remove();
        }
    }

    int liveCount(String portalId) {
        List<Entity> entities = live.get("gate:" + portalId);
        return entities == null ? 0 : (int) entities.stream().filter(Entity::isValid).count();
    }

    /** Can this player see this gate's entities right now? Used by /gate test. */
    boolean seenBy(Player player, String portalId) {
        List<Entity> entities = live.get("gate:" + portalId);
        return entities != null && !entities.isEmpty() && entities.stream().allMatch(player::canSee);
    }

    private void spawnGate(Gate p, Station.Platform platform, List<Entity> out) {
        Settings settings = plugin.settings();
        Settings.Look look = settings.look(plugin.lookName(p));
        World world = Bukkit.getWorld(p.world);
        Location at = new Location(world, p.x + 0.5, p.y, p.z + 0.5);
        float half = look.width() / 2f, h = look.height();

        // Frames use blocks from the world beyond, unless the platform keeps to its theme.
        Random random = new Random(p.id.hashCode());
        List<BlockData> floor = platform == null ? List.of(look.frame()) : platform.theme();
        List<BlockData> frame = new ArrayList<>();
        boolean themedOnly = platform != null && !platform.worldBlocks();
        if (!themedOnly) {
            for (String block : p.palette) {
                if (!NEVER.contains(block)) frame.add(Bukkit.createBlockData(block));
            }
        }
        if (frame.isEmpty()) frame = floor;
        List<BlockData> accent = platform != null ? platform.theme() : frame;

        long seed = plugin.station().seed();
        GateForge.Blueprint print = p.isFargate() ? GateForge.fargate(half, h)
            : platform != null ? GateForge.station(platform.seed(), seed ^ p.id.hashCode(), half, h, platform.design())
            : GateForge.world(seed ^ p.id.hashCode(), half, h, settings.standardWorldGates);
        BlockData glass = Bukkit.createBlockData(GateForge.GLASS[print.glass()] + "_stained_glass");
        BlockData glow = Bukkit.createBlockData(GLOWS.get(Math.floorMod(seed ^ p.id.hashCode(), GLOWS.size())));
        boolean dead = plugin.isDead(p);
        for (GateForge.Part part : print.parts()) {
            BlockData block;
            if (dead) { // a cracked frame around an empty opening, with no light left in it
                if (part.role() == GateForge.Role.OPENING || part.role() == GateForge.Role.GLOW) continue;
                block = settings.deadFrame;
            } else if (p.isFargate()) {
                block = Bukkit.createBlockData(switch (part.role()) {
                    case FRAME -> "yellow_concrete";
                    case ACCENT -> "black_concrete";
                    case OPENING -> "red_stained_glass";
                    case GLOW -> "shroomlight";
                });
            } else {
                block = switch (part.role()) {
                    case FRAME -> pick(frame, random);
                    case ACCENT -> pick(accent, random);
                    case OPENING -> glass;
                    case GLOW -> glow;
                };
            }
            out.add(part.spin() ? spawnDiamond(at, p, part, block) : spawnBox(at, p, part.a0(), part.a1(), part.v0(), part.v1(), part.d0(), part.d1(), block));
        }
        String label = plugin.labelOf(p);
        if (!label.isBlank()) out.add(spawnText(at.clone().add(0, print.top() + 0.4, 0), label, look.labelScale(), Display.Billboard.CENTER));

    }

    /** A floor of themed tiles in front of a station gate. No collision, and they replace no blocks. */
    private void spawnFloor(Gate p, Station.Platform platform, List<Entity> out) {
        Settings settings = plugin.settings();
        Location at = new Location(Bukkit.getWorld(p.world), p.x + 0.5, p.y, p.z + 0.5);
        Random random = new Random(p.id.hashCode() * 31L);
        List<BlockData> floor = platform.theme();
        if (settings.tileDepth > 0) {
            int sign = p.facing == BlockFace.SOUTH || p.facing == BlockFace.EAST ? 1 : -1;
            float reach = settings.stationSpacing / 2f, tile = settings.tileSize;
            for (float a = -reach; a < reach - 0.01f; a += tile) {
                float a1 = Math.min(a + tile, reach);
                for (float d = 0; d < settings.tileDepth; d += tile) {
                    float d0 = sign > 0 ? d : -d - tile, d1 = d0 + tile;
                    out.add(spawnBox(at, p, a, a1, 0.002f, 0.04f, d0, d1, pick(floor, random)));
                }
            }
        }
    }

    /** The centrepiece, in a theme picked by the seed, with the station's name floating above it. */
    private void spawnMonument(Location at, List<Entity> out) {
        Settings settings = plugin.settings();
        long seed = plugin.station().seed();
        Random random = GateForge.random(seed);
        List<List<BlockData>> all = new ArrayList<>(settings.themes.values());
        List<BlockData> theme = all.get(random.nextInt(all.size()));
        BlockData glow = Bukkit.createBlockData(GLOWS.get(random.nextInt(GLOWS.size())));
        GateForge.Blueprint print = GateForge.monument(seed);
        Gate facing = new Gate("station", at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), BlockFace.NORTH, at.getWorld().getName());
        for (GateForge.Part part : print.parts()) {
            BlockData block = part.role() == GateForge.Role.GLOW ? glow : pick(theme, random);
            out.add(part.spin() ? spawnDiamond(at, facing, part, block) : spawnBox(at, facing, part.a0(), part.a1(), part.v0(), part.v1(), part.d0(), part.d1(), block));
        }
        if (!settings.stationLabel.isBlank()) {
            out.add(spawnText(at.clone().add(0, print.top() + 0.5, 0), settings.stationLabel, 3f, Display.Billboard.CENTER));
        }
    }

    /** Beside the gate, on the side you walk up to. */
    private static Location chestSpot(World world, Gate p, Settings.Look look) {
        double along = look.width() / 2.0 + 0.9;
        return new Location(world, p.x + 0.5 + (p.alongX() ? along : p.facing.getModX()), p.y, p.z + 0.5 + (p.alongX() ? p.facing.getModZ() : along));
    }

    /** A chest that's only a picture and a clickable box. An item display, since a block display can't draw a chest. */
    private void spawnChest(Location at, Gate p, List<Entity> out) {
        out.add(at.getWorld().spawn(at.clone().add(0, 0.45, 0), ItemDisplay.class, e -> {
            prepare(e);
            e.setItemStack(ItemStack.of(Material.CHEST));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.9f, 0.9f, 0.9f), new AxisAngle4f()));
        }));
        out.add(spawnText(at.clone().add(0, 1.1, 0), Labels.TRIBUTE, 0.8f, Display.Billboard.CENTER));
        out.add(at.getWorld().spawn(at, Interaction.class, e -> {
            prepareAny(e);
            e.setInteractionWidth(1f);
            e.setInteractionHeight(1f);
            e.addScoreboardTag(CHEST_TAG);
            e.addScoreboardTag(CHEST_TAG + ":" + p.id);
        }));
    }

    /** A station sign: a board on two posts, spanning the platform, facing the aisle. */
    private void spawnSign(Station.Platform platform, Gate middle, List<Entity> out) {
        Settings settings = plugin.settings();
        World world = Bukkit.getWorld(middle.world);
        Location at = new Location(world, middle.x + 0.5, middle.y, middle.z + 0.5);
        float span = Math.max(4f, (platform.gates().size() * settings.stationSpacing) / 2f - 1f);
        float span2 = Math.min(span, 9f);
        float top = settings.look(plugin.lookName(middle)).height() + 5.5f;
        Random random = new Random(platform.group().hashCode());
        BlockData post = pick(platform.theme(), random);
        BlockData board = Material.POLISHED_BLACKSTONE.createBlockData();
        out.add(spawnBox(at, middle, -span2, -span2 + 0.3f, 0, top, -0.15f, 0.15f, post));
        out.add(spawnBox(at, middle, span2 - 0.3f, span2, 0, top, -0.15f, 0.15f, post));
        out.add(spawnBox(at, middle, -span2, span2, top - 1.4f, top, -0.1f, 0.1f, board));

        int sign = middle.facing == BlockFace.SOUTH || middle.facing == BlockFace.EAST ? 1 : -1;
        Location text = at.clone().add(middle.alongX() ? 0 : sign * 0.14, top - 1.25, middle.alongX() ? sign * 0.14 : 0);
        text.setDirection(middle.facing.getDirection());
        String label = Labels.platform(platform.number(), platform.label());
        out.add(spawnText(text, label, 1.6f, Display.Billboard.FIXED));
    }

    private static BlockData pick(List<BlockData> theme, Random random) {
        return theme.get(random.nextInt(theme.size()));
    }

    /**
     * Spawns a block display stretched over a box given relative to the anchor: "a" runs along the
     * gate's plane, "v" up, and "d" across it.
     */
    private Entity spawnBox(Location at, Gate p, float a0, float a1, float v0, float v1, float d0, float d1, BlockData block) {
        boolean alongX = p.alongX();
        Vector3f offset = alongX ? new Vector3f(a0, v0, d0) : new Vector3f(d0, v0, a0);
        Vector3f scale = alongX ? new Vector3f(a1 - a0, v1 - v0, d1 - d0) : new Vector3f(d1 - d0, v1 - v0, a1 - a0);
        Location flat = at.clone();
        flat.setYaw(0);
        flat.setPitch(0);
        return at.getWorld().spawn(flat, BlockDisplay.class, e -> {
            prepare(e);
            e.setBlock(block);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(offset, new AxisAngle4f(), scale, new AxisAngle4f()));
        });
    }

    /** A cube stood on its corner, for lanterns and floating shards. */
    private Entity spawnDiamond(Location at, Gate p, GateForge.Part part, BlockData block) {
        float size = (part.a1() - part.a0() + part.v1() - part.v0()) / 2;
        float along = (part.a0() + part.a1()) / 2, across = (part.d0() + part.d1()) / 2, up = (part.v0() + part.v1()) / 2;
        Quaternionf turn = new Quaternionf().rotateY((float) Math.PI / 4).rotateX((float) Math.atan(Math.sqrt(0.5)));
        Vector3f middle = turn.transform(new Vector3f(size / 2, size / 2, size / 2));
        Vector3f offset = p.alongX() ? new Vector3f(along, up, across) : new Vector3f(across, up, along);
        Location flat = at.clone();
        flat.setYaw(0);
        flat.setPitch(0);
        return at.getWorld().spawn(flat, BlockDisplay.class, e -> {
            prepare(e);
            e.setBlock(block);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(offset.sub(middle), turn, new Vector3f(size, size, size), new Quaternionf()));
        });
    }

    private Entity spawnText(Location at, String miniMessage, float scale, Display.Billboard billboard) {
        Component text = MiniMessage.miniMessage().deserialize(miniMessage);
        return at.getWorld().spawn(at, TextDisplay.class, e -> {
            prepare(e);
            e.text(text);
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setBillboard(billboard);
            e.setBrightness(new Display.Brightness(15, 15));
            Labels.style(e, plugin.settings().labelBacking);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    private static void prepare(Display e) {
        prepareAny(e);
    }

    private static void prepareAny(Entity e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        e.addScoreboardTag(TAG);
        e.setGravity(false);
        e.setInvulnerable(true);
        e.setSilent(true);
    }
}
