package com.extantape.waycave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The plan of one Waycave (K649, D194): which chambers it has, which tunnels join them and where each world group's
 * gates stand. Pure arithmetic with no server, so every rule is unit tested over many seeds.
 *
 * <p>The plan is a graph first. The start chamber is where every trail begins. Each world group grows one chamber on a
 * new branch off whatever exists, joined by a winding tunnel, and a few side chambers and loops keep it from being a
 * maze of dead ends. A plan is grown from a saved plan: nothing already in it ever moves, so the way to a world stays
 * where players learned it. The same seed and the same groups in the same order give the same plan.
 *
 * <p>Every chamber has a fixed number of doors, spaced evenly round its wall. A tunnel uses one door at each end.
 * Gate sockets sit on a ring inside the wall, in the arcs between doors, so a tunnel can never open onto a gate.
 */
final class CaveLayout {
    enum Kind { START, GROUP, SIDE }

    enum Feature { NONE, POOL, GROVE }

    /** A chamber: a blob of this radius round (x, z). Door 0 faces the parent, or a seeded way at the start. */
    record Chamber(int id, Kind kind, String group, int x, int z, int radius, Feature feature, double baseAngle, int doors) {
        double doorAngle(int door) {
            return baseAngle + door * 2 * Math.PI / doors;
        }

        /** Where a door meets the wall, on the chamber's edge. */
        Pt doorPoint(int door) {
            double a = doorAngle(door);
            return new Pt(x + Math.cos(a) * (radius - 1), z + Math.sin(a) * (radius - 1));
        }
    }

    record Pt(double x, double z) {}

    /** A winding tunnel, as the points down its middle from door to door. */
    record Tunnel(int a, int aDoor, int b, int bDoor, List<Pt> points, double radius) {}

    /** Where one gate can stand. facing is the way people walk up to it: towards the chamber's middle. */
    record Socket(int chamber, int x, int z, char facing) {}

    /** The saved plan. */
    record Plan(long seed, List<Chamber> chambers, List<Tunnel> tunnels, List<Socket> sockets) {
        Chamber chamber(int id) {
            return chambers.get(id);
        }

        Chamber ofGroup(String group) {
            for (Chamber c : chambers) if (group.equals(c.group())) return c;
            return null;
        }

        Chamber start() {
            return chambers.getFirst();
        }

        List<Socket> socketsOf(int chamber) {
            return sockets.stream().filter(s -> s.chamber() == chamber).toList();
        }

        boolean doorUsed(int chamber, int door) {
            for (Tunnel t : tunnels) {
                if ((t.a() == chamber && t.aDoor() == door) || (t.b() == chamber && t.bDoor() == door)) return true;
            }
            return false;
        }

        /** The tunnel joining two chambers, either way round, or null. */
        Tunnel between(int a, int b) {
            for (Tunnel t : tunnels) if ((t.a() == a && t.b() == b) || (t.a() == b && t.b() == a)) return t;
            return null;
        }
    }

    static final int START_RADIUS = 16;
    /** Least blocks between the middles of two gates in a chamber. Gates tested for "too near" at six, in each direction. */
    static final int GATE_SPACING = 11;
    /** A socket sits this far inside the wall. */
    static final int SOCKET_INSET = 6;
    /** How far from a door's middle round the ring a socket may stand. */
    static final double DOOR_CLEARANCE = 6.5;
    static final int SIDE_RADIUS = 10;
    /** Spare sockets a new group chamber is sized for, so a world added later still has a place. */
    static final int SPARE_SOCKETS = 2;
    static final int LIMIT = 130;

    private CaveLayout() {}

    /** A brand new plan: the start chamber and two side chambers, one with a pool and one with a grove. */
    static Plan fresh(long seed) {
        List<Chamber> chambers = new ArrayList<>();
        Random r = new Random(seed ^ 0x57415943415645L);
        double base = r.nextDouble() * Math.PI * 2;
        Chamber start = new Chamber(0, Kind.START, null, 0, 0, START_RADIUS, Feature.NONE, base, 3);
        chambers.add(start);
        Plan plan = new Plan(seed, chambers, new ArrayList<>(), new ArrayList<>());
        // The start chamber holds one socket, for a gate to another server.
        plan.sockets().addAll(sockets(start));
        for (int i = 0; i < 2; i++) {
            Plan grown = grow(plan, Kind.SIDE, "side-" + i, 0);
            if (grown == null) break;
            plan = grown;
        }
        return plan;
    }

    /**
     * The plan with this group's chamber added, if it has none. Existing chambers, tunnels and sockets are never
     * changed. {@code gates} is how many gates the group has now: the chamber is sized for those and a few spare.
     * Returns the plan unchanged when there's no room left (the caller tells an admin).
     */
    static Plan with(Plan plan, String group, int gates) {
        if (plan.ofGroup(group) != null) return plan;
        Plan grown = grow(plan, Kind.GROUP, group, gates);
        if (grown == null) return plan;
        Plan looped = loop(grown, group);
        return looped == null ? grown : looped;
    }

    /** Sockets a chamber of this radius and door count holds. */
    static int capacity(int radius, int doors) {
        return sockets(new Chamber(0, Kind.GROUP, "x", 0, 0, radius, Feature.NONE, 0, doors)).size();
    }

    private static int radiusFor(int gates) {
        for (int r = 12; r <= 28; r++) if (capacity(r, 3) >= gates + SPARE_SOCKETS) return r;
        return 28;
    }

    // --- growing ---

    private static Plan grow(Plan plan, Kind kind, String group, int gates) {
        long salt = group.hashCode() * 31L + plan.chambers().size();
        Random rng = new Random(plan.seed() ^ (salt * 0x9E3779B97F4A7C15L));
        int radius = kind == Kind.SIDE ? SIDE_RADIUS + rng.nextInt(3) : radiusFor(gates);
        List<Chamber> open = new ArrayList<>();
        for (Chamber c : plan.chambers()) if (freeDoors(plan, c) > 0) open.add(c);
        if (open.isEmpty()) return null;
        for (int attempt = 0; attempt < 400; attempt++) {
            Chamber parent = open.get((attempt + rng.nextInt(open.size())) % open.size());
            List<Integer> doors = new ArrayList<>();
            for (int d = 0; d < parent.doors(); d++) if (!plan.doorUsed(parent.id(), d)) doors.add(d);
            int door = doors.get(rng.nextInt(doors.size()));
            double angle = parent.doorAngle(door);
            double length = 16 + rng.nextInt(10) + attempt / 8.0;
            double reach = parent.radius() + length + radius;
            int cx = parent.x() + (int) Math.round(Math.cos(angle) * reach), cz = parent.z() + (int) Math.round(Math.sin(angle) * reach);
            if (Math.max(Math.abs(cx), Math.abs(cz)) + radius > LIMIT) continue;
            int id = plan.chambers().size();
            double base = Math.atan2(parent.z() - cz, parent.x() - cx);
            Feature feature = kind == Kind.START ? Feature.NONE : (id % 2 == 0 ? Feature.POOL : Feature.GROVE);
            Chamber child = new Chamber(id, kind, kind == Kind.SIDE ? null : group, cx, cz, radius, feature, base, 3);
            List<Pt> points = winding(parent.doorPoint(door), child.doorPoint(0), rng);
            Tunnel tunnel = new Tunnel(parent.id(), door, id, 0, points, 1.75 + rng.nextInt(5) * 0.25);
            if (!fits(plan, child, tunnel)) continue;
            List<Chamber> chambers = new ArrayList<>(plan.chambers());
            chambers.add(child);
            List<Tunnel> tunnels = new ArrayList<>(plan.tunnels());
            tunnels.add(tunnel);
            Plan next = new Plan(plan.seed(), chambers, tunnels, new ArrayList<>(plan.sockets()));
            if (kind != Kind.SIDE) next.sockets().addAll(sockets(child));
            return next;
        }
        return null;
    }

    private static int freeDoors(Plan plan, Chamber c) {
        int free = 0;
        for (int d = 0; d < c.doors(); d++) if (!plan.doorUsed(c.id(), d)) free++;
        return free;
    }

    /** Adds one loop back to an older chamber when two are close and nothing is in the way. */
    private static Plan loop(Plan plan, String group) {
        Chamber fresh = plan.ofGroup(group);
        if (fresh == null) return null;
        Random rng = new Random(plan.seed() ^ (group.hashCode() * 0x632BE59BD9B4E019L));
        if (rng.nextInt(100) >= 60) return null;
        List<Chamber> others = new ArrayList<>(plan.chambers());
        others.removeIf(c -> c.id() == fresh.id() || plan.between(c.id(), fresh.id()) != null || freeDoors(plan, c) == 0);
        Collections.shuffle(others, rng);
        for (Chamber other : others) {
            if (Math.hypot(other.x() - fresh.x(), other.z() - fresh.z()) > 75 || freeDoors(plan, fresh) == 0) continue;
            for (int fd = 0; fd < fresh.doors(); fd++) {
                if (plan.doorUsed(fresh.id(), fd)) continue;
                for (int od = 0; od < other.doors(); od++) {
                    if (plan.doorUsed(other.id(), od)) continue;
                    Pt a = fresh.doorPoint(fd), b = other.doorPoint(od);
                    // Each end steps straight out of its door before they join, so the tunnel never cuts back through a chamber.
                    Pt outA = new Pt(a.x() + Math.cos(fresh.doorAngle(fd)) * 9, a.z() + Math.sin(fresh.doorAngle(fd)) * 9);
                    Pt outB = new Pt(b.x() + Math.cos(other.doorAngle(od)) * 9, b.z() + Math.sin(other.doorAngle(od)) * 9);
                    List<Pt> points = new ArrayList<>();
                    points.add(a);
                    points.addAll(winding(outA, outB, rng));
                    points.add(b);
                    Tunnel t = new Tunnel(fresh.id(), fd, other.id(), od, points, 1.75 + rng.nextInt(5) * 0.25);
                    if (!fitsLoop(plan, t)) continue;
                    List<Tunnel> tunnels = new ArrayList<>(plan.tunnels());
                    tunnels.add(t);
                    return new Plan(plan.seed(), plan.chambers(), tunnels, plan.sockets());
                }
            }
        }
        return null;
    }

    /** Points from a to b with a gentle sideways wander, fading to nothing at both ends. About one point every five blocks. */
    static List<Pt> winding(Pt a, Pt b, Random rng) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), len = Math.hypot(dx, dz);
        int steps = Math.max(2, (int) Math.round(len / 5));
        double px = -dz / len, pz = dx / len, off = 0;
        List<Pt> out = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            off = off * 0.6 + (rng.nextDouble() - 0.5) * 5.0;
            double fade = Math.sin(Math.PI * t);
            out.add(new Pt(a.x() + dx * t + px * off * fade, a.z() + dz * t + pz * off * fade));
        }
        return out;
    }

    // --- room checks ---

    private static boolean fits(Plan plan, Chamber child, Tunnel tunnel) {
        for (Chamber other : plan.chambers()) {
            if (Math.hypot(other.x() - child.x(), other.z() - child.z()) < other.radius() + child.radius() + 12) return false;
        }
        for (Pt p : sample(tunnel.points(), 2)) {
            for (Chamber other : plan.chambers()) {
                if (other.id() == tunnel.a()) continue;
                if (Math.hypot(other.x() - p.x(), other.z() - p.z()) < other.radius() + 8) return false;
            }
        }
        return clearOfTunnels(plan, tunnel, plan.chamber(tunnel.a()), child) && chamberClearOfTunnels(plan, child);
    }

    private static boolean fitsLoop(Plan plan, Tunnel t) {
        // A loop must come at each chamber from outside, through its door, never across its floor.
        for (Pt p : sample(t.points(), 2)) {
            for (int end = 0; end < 2; end++) {
                Chamber c = plan.chamber(end == 0 ? t.a() : t.b());
                Pt door = c.doorPoint(end == 0 ? t.aDoor() : t.bDoor());
                if (Math.hypot(p.x() - door.x(), p.z() - door.z()) > 11 && Math.hypot(p.x() - c.x(), p.z() - c.z()) < c.radius() + 4) return false;
            }
        }
        for (Pt p : sample(t.points(), 2)) {
            for (Chamber other : plan.chambers()) {
                if (other.id() == t.a() || other.id() == t.b()) continue;
                if (Math.hypot(other.x() - p.x(), other.z() - p.z()) < other.radius() + 8) return false;
            }
        }
        return clearOfTunnels(plan, t, plan.chamber(t.a()), plan.chamber(t.b()));
    }

    /** No point of the new tunnel, outside its own two chambers, within ten blocks of another tunnel. */
    private static boolean clearOfTunnels(Plan plan, Tunnel t, Chamber ea, Chamber eb) {
        for (Pt p : sample(t.points(), 2)) {
            if (inside(p, ea, 4) || inside(p, eb, 4)) continue;
            for (Tunnel o : plan.tunnels()) {
                Chamber oa = plan.chamber(o.a()), ob = plan.chamber(o.b());
                for (Pt q : sample(o.points(), 2)) {
                    if (inside(q, oa, 4) || inside(q, ob, 4)) continue;
                    if (Math.hypot(p.x() - q.x(), p.z() - q.z()) < 10) return false;
                }
            }
        }
        return true;
    }

    private static boolean chamberClearOfTunnels(Plan plan, Chamber c) {
        for (Tunnel o : plan.tunnels()) {
            Chamber oa = plan.chamber(o.a()), ob = plan.chamber(o.b());
            for (Pt q : sample(o.points(), 2)) {
                if (inside(q, oa, 4) || inside(q, ob, 4)) continue;
                if (Math.hypot(q.x() - c.x(), q.z() - c.z()) < c.radius() + 8) return false;
            }
        }
        return true;
    }

    private static boolean inside(Pt p, Chamber c, int margin) {
        return Math.hypot(p.x() - c.x(), p.z() - c.z()) < c.radius() + margin;
    }

    /** Points along a polyline, about every {@code step} blocks. */
    static List<Pt> sample(List<Pt> line, double step) {
        List<Pt> out = new ArrayList<>();
        for (int i = 0; i + 1 < line.size(); i++) {
            Pt a = line.get(i), b = line.get(i + 1);
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int n = Math.max(1, (int) Math.ceil(len / step));
            for (int k = 0; k < n; k++) out.add(new Pt(a.x() + (b.x() - a.x()) * k / n, a.z() + (b.z() - a.z()) * k / n));
        }
        out.add(line.getLast());
        return out;
    }

    // --- sockets ---

    /**
     * Sockets for a chamber: on a ring {@link #SOCKET_INSET} inside the wall, in the arcs between doors, at least
     * {@link #GATE_SPACING} apart and {@link #DOOR_CLEARANCE} from any door.
     */
    static List<Socket> sockets(Chamber c) {
        List<Socket> out = new ArrayList<>();
        if (c.kind() == Kind.SIDE) return out;
        double ring = c.radius() - SOCKET_INSET;
        if (ring < 4) return out;
        double halfDoor = DOOR_CLEARANCE / ring;
        double arc = 2 * Math.PI / c.doors() - 2 * halfDoor;
        if (arc < 0) return out;
        double lengthBlocks = arc * ring;
        int count = (int) Math.floor(lengthBlocks / GATE_SPACING) + 1;
        if (c.kind() == Kind.START) count = 1; // one socket in the start chamber, for the gate to another server
        for (int door = 0; door < c.doors(); door++) {
            double from = c.doorAngle(door) + halfDoor;
            for (int i = 0; i < count; i++) {
                double a = count == 1 ? from + arc / 2 : from + arc * i / (count - 1);
                int x = c.x() + (int) Math.round(Math.cos(a) * ring), z = c.z() + (int) Math.round(Math.sin(a) * ring);
                out.add(new Socket(c.id(), x, z, facingToward(x - c.x(), z - c.z())));
            }
            if (c.kind() == Kind.START) break;
        }
        return out;
    }

    /** The way to stand to face the middle from an offset: the same rule the return gates use. */
    static char facingToward(int offsetX, int offsetZ) {
        return Math.abs(offsetX) > Math.abs(offsetZ) ? (offsetX > 0 ? 'W' : 'E') : (offsetZ > 0 ? 'N' : 'S');
    }

    // --- saving ---

    static Map<String, Object> toMap(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", plan.seed());
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Chamber c : plan.chambers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind().name());
            if (c.group() != null) m.put("group", c.group());
            m.put("x", c.x());
            m.put("z", c.z());
            m.put("radius", c.radius());
            m.put("feature", c.feature().name());
            m.put("base-angle", c.baseAngle());
            m.put("doors", c.doors());
            cs.add(m);
        }
        out.put("chambers", cs);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Tunnel t : plan.tunnels()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", t.a());
            m.put("a-door", t.aDoor());
            m.put("b", t.b());
            m.put("b-door", t.bDoor());
            m.put("radius", t.radius());
            List<Double> pts = new ArrayList<>();
            for (Pt p : t.points()) {
                pts.add(p.x());
                pts.add(p.z());
            }
            m.put("points", pts);
            ts.add(m);
        }
        out.put("tunnels", ts);
        List<Map<String, Object>> ss = new ArrayList<>();
        for (Socket s : plan.sockets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chamber", s.chamber());
            m.put("x", s.x());
            m.put("z", s.z());
            m.put("facing", String.valueOf(s.facing()));
            ss.add(m);
        }
        out.put("sockets", ss);
        return out;
    }

    /** Reads a saved plan. Throws on anything missing or wrong, so a damaged file is never half used. */
    @SuppressWarnings("unchecked")
    static Plan fromMap(Map<?, ?> in) {
        long seed = ((Number) in.get("seed")).longValue();
        List<Chamber> chambers = new ArrayList<>();
        int id = 0;
        for (Object o : (List<?>) in.get("chambers")) {
            Map<String, Object> m = (Map<String, Object>) o;
            chambers.add(new Chamber(id++, Kind.valueOf((String) m.get("kind")), (String) m.get("group"), ((Number) m.get("x")).intValue(),
                ((Number) m.get("z")).intValue(), ((Number) m.get("radius")).intValue(), Feature.valueOf((String) m.get("feature")),
                ((Number) m.get("base-angle")).doubleValue(), ((Number) m.get("doors")).intValue()));
        }
        List<Tunnel> tunnels = new ArrayList<>();
        for (Object o : (List<?>) in.get("tunnels")) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<Pt> pts = new ArrayList<>();
            List<?> raw = (List<?>) m.get("points");
            for (int i = 0; i + 1 < raw.size(); i += 2) pts.add(new Pt(((Number) raw.get(i)).doubleValue(), ((Number) raw.get(i + 1)).doubleValue()));
            tunnels.add(new Tunnel(((Number) m.get("a")).intValue(), ((Number) m.get("a-door")).intValue(), ((Number) m.get("b")).intValue(),
                ((Number) m.get("b-door")).intValue(), List.copyOf(pts), ((Number) m.get("radius")).doubleValue()));
        }
        List<Socket> sockets = new ArrayList<>();
        for (Object o : (List<?>) in.get("sockets")) {
            Map<String, Object> m = (Map<String, Object>) o;
            sockets.add(new Socket(((Number) m.get("chamber")).intValue(), ((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue(),
                ((String) m.get("facing")).charAt(0)));
        }
        if (chambers.isEmpty() || chambers.getFirst().kind() != Kind.START) throw new IllegalArgumentException("the plan has no start chamber");
        return new Plan(seed, chambers, tunnels, sockets);
    }
}
