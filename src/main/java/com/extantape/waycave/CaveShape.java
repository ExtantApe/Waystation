package com.extantape.waycave;

import com.extantape.waycave.CaveLayout.Chamber;
import com.extantape.waycave.CaveLayout.Plan;
import com.extantape.waycave.CaveLayout.Pt;
import com.extantape.waycave.CaveLayout.Socket;
import com.extantape.waycave.CaveLayout.Tunnel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A plan turned into columns: for each x and z, whether it's open, how high its floor is and how high its ceiling.
 * Pure, so the rules are tested without a server and the builder only has to copy it into blocks.
 *
 * <p>The floor is at one of two heights, so no step is ever more than one block. Floors are flat round every gate
 * socket, tunnel mouth and the start chamber's middle. Chambers have domed ceilings, tunnels are four blocks high.
 * Pools and groves are marked as blocked columns, kept away from every path the plan relies on.
 *
 * <p>Light is simulated here too: glowing berry vines are placed until every walkable block is lit to at least
 * {@link #MIN_LIGHT}, which also keeps mobs from spawning.
 */
final class CaveShape {
    /** Column kinds. */
    static final byte SOLID = 0, WALK = 1, BLOCKED = 2, POOL = 3;
    /** The dimmest any walkable block is allowed to be (the design's floor, D194 notes). */
    static final int MIN_LIGHT = 9;
    static final int VINE_LIGHT = 14;
    private static final int PAD = 6;

    final Plan plan;
    final int minX, minZ, width, depth;
    private final byte[] kind;
    private final byte[] floor;
    private final byte[] top;
    /** Columns with a hanging vine: the top block of the column, down to this height above the floor. */
    private final byte[] vineBottom;
    private final long[] noiseSeeds;

    CaveShape(Plan plan) {
        this.plan = plan;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Chamber c : plan.chambers()) {
            x0 = Math.min(x0, c.x() - c.radius());
            x1 = Math.max(x1, c.x() + c.radius());
            z0 = Math.min(z0, c.z() - c.radius());
            z1 = Math.max(z1, c.z() + c.radius());
        }
        for (Tunnel t : plan.tunnels()) {
            for (Pt p : t.points()) {
                x0 = Math.min(x0, (int) p.x() - 6);
                x1 = Math.max(x1, (int) p.x() + 6);
                z0 = Math.min(z0, (int) p.z() - 6);
                z1 = Math.max(z1, (int) p.z() + 6);
            }
        }
        minX = x0 - PAD;
        minZ = z0 - PAD;
        width = x1 - x0 + 2 * PAD + 1;
        depth = z1 - z0 + 2 * PAD + 1;
        kind = new byte[width * depth];
        floor = new byte[width * depth];
        top = new byte[width * depth];
        vineBottom = new byte[width * depth];
        long s = plan.seed();
        noiseSeeds = new long[] {s * 31 + 1, s * 31 + 2, s * 31 + 3, s * 31 + 4, s * 31 + 5};
        carve();
        furnish();
        trim();
        light();
    }

    // --- queries ---

    boolean inBounds(int x, int z) {
        return x >= minX && z >= minZ && x < minX + width && z < minZ + depth;
    }

    private int at(int x, int z) {
        return (x - minX) * depth + (z - minZ);
    }

    byte kindAt(int x, int z) {
        return inBounds(x, z) ? kind[at(x, z)] : SOLID;
    }

    boolean open(int x, int z) {
        return kindAt(x, z) != SOLID;
    }

    boolean walkable(int x, int z) {
        return kindAt(x, z) == WALK;
    }

    /** Feet height of the floor, 0 or 1 above the cave's base level. Only for open columns. */
    int floorAt(int x, int z) {
        return floor[at(x, z)];
    }

    /** Height of the top air block, above the base level. Only for open columns. */
    int topAt(int x, int z) {
        return top[at(x, z)];
    }

    /** Height of the lowest vine block in this column, above the base level, or -1 for none. */
    int vineBottomAt(int x, int z) {
        return inBounds(x, z) && vineBottom[at(x, z)] > 0 ? vineBottom[at(x, z)] : -1;
    }

    /** The number of open columns. */
    int openCount() {
        int n = 0;
        for (byte k : kind) if (k != SOLID) n++;
        return n;
    }

    // --- carving ---

    private static double noise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = lattice(seed, x0, z0), b = lattice(seed, x0 + 1, z0), c = lattice(seed, x0, z0 + 1), d = lattice(seed, x0 + 1, z0 + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static double lattice(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static int hash(long seed, int x, int z) {
        return (int) (lattice(seed, x, z) * 1_000_000);
    }

    private void carve() {
        int[] height = new int[width * depth];
        for (Chamber c : plan.chambers()) {
            int peak = c.kind() == CaveLayout.Kind.START ? 12 : Math.max(7, Math.min(11, 4 + c.radius() / 2));
            for (int x = c.x() - c.radius() - 4; x <= c.x() + c.radius() + 4; x++) {
                for (int z = c.z() - c.radius() - 4; z <= c.z() + c.radius() + 4; z++) {
                    double dd = Math.hypot(x - c.x(), z - c.z());
                    double wall = c.radius() + (noise(noiseSeeds[0], x / 4.5, z / 4.5) - 0.5) * 3.2;
                    if (dd >= wall) continue;
                    double t = Math.min(1.0, dd / c.radius());
                    int h = 4 + (int) Math.round((peak - 4) * (1 - t * t) + noise(noiseSeeds[1], x / 3.0, z / 3.0));
                    open(x, z, Math.max(4, h), height);
                }
            }
        }
        for (Tunnel t : plan.tunnels()) {
            for (int i = 0; i + 1 < t.points().size(); i++) {
                Pt a = t.points().get(i), b = t.points().get(i + 1);
                int bx0 = (int) Math.floor(Math.min(a.x(), b.x()) - t.radius() - 2), bx1 = (int) Math.ceil(Math.max(a.x(), b.x()) + t.radius() + 2);
                int bz0 = (int) Math.floor(Math.min(a.z(), b.z()) - t.radius() - 2), bz1 = (int) Math.ceil(Math.max(a.z(), b.z()) + t.radius() + 2);
                for (int x = bx0; x <= bx1; x++) {
                    for (int z = bz0; z <= bz1; z++) {
                        double limit = t.radius() + (noise(noiseSeeds[2], x / 3.5, z / 3.5) - 0.5) * 0.9;
                        if (distance(x, z, a, b) <= limit) open(x, z, 4 + (noise(noiseSeeds[1], x / 3.0, z / 3.0) > 0.7 ? 1 : 0), height);
                    }
                }
            }
        }
        // Alcoves: every gate socket has open, high, flat ground all round it, whatever the wall noise did.
        for (Socket s : plan.sockets()) {
            for (int x = s.x() - 5; x <= s.x() + 5; x++) {
                for (int z = s.z() - 5; z <= s.z() + 5; z++) {
                    if (Math.hypot(x - s.x(), z - s.z()) <= 5.0) open(x, z, 10, height);
                }
            }
        }
        // Floors: mostly level, with the odd one-block rise. Level where gates, doors and the monument need it.
        for (int x = minX; x < minX + width; x++) {
            for (int z = minZ; z < minZ + depth; z++) {
                int i = at(x, z);
                if (kind[i] == SOLID) continue;
                boolean rise = noise(noiseSeeds[3], x / 5.0, z / 5.0) > 0.62 && !flatZone(x, z);
                floor[i] = (byte) (rise ? 1 : 0);
                top[i] = (byte) (floor[i] + height[i] - 1);
            }
        }
    }

    private void open(int x, int z, int h, int[] height) {
        if (!inBounds(x, z)) return;
        int i = at(x, z);
        kind[i] = WALK;
        height[i] = Math.max(height[i], h);
    }

    private static double distance(double px, double pz, Pt a, Pt b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - a.x()) * dx + (pz - a.z()) * dz) / len2));
        return Math.hypot(px - (a.x() + dx * t), pz - (a.z() + dz * t));
    }

    /** Ground that stays level: round sockets and doors, and under the monument in the start chamber. */
    private boolean flatZone(int x, int z) {
        for (Socket s : plan.sockets()) if (Math.hypot(x - s.x(), z - s.z()) <= 6) return true;
        Chamber start = plan.start();
        if (Math.hypot(x - start.x(), z - start.z()) <= 7) return true;
        for (Chamber c : plan.chambers()) {
            for (int d = 0; d < c.doors(); d++) {
                Pt p = c.doorPoint(d);
                if (Math.hypot(x - p.x(), z - p.z()) <= 5) return true;
            }
        }
        return false;
    }

    /** Walkable pockets nobody can reach from the start (a bubble the wall noise left) are filled in, so no ground is cut off. */
    private void trim() {
        Chamber start = plan.start();
        Set<Long> reach = reachableFrom(start.x(), start.z());
        for (int x = minX; x < minX + width; x++) {
            for (int z = minZ; z < minZ + depth; z++) {
                if (kind[at(x, z)] == WALK && !reach.contains(key(x, z))) kind[at(x, z)] = SOLID;
            }
        }
        // A pool or grove column with no walkable neighbour left is a hole in nothing: fill it too.
        for (int x = minX; x < minX + width; x++) {
            for (int z = minZ; z < minZ + depth; z++) {
                byte k = kind[at(x, z)];
                if (k != BLOCKED && k != POOL) continue;
                boolean touches = walkable(x + 1, z) || walkable(x - 1, z) || walkable(x, z + 1) || walkable(x, z - 1);
                if (!touches) kind[at(x, z)] = SOLID;
            }
        }
    }

    // --- pools and groves ---

    /** Straight lines from a chamber's middle to its doors and sockets, which pools and groves stay off. */
    private List<Pt[]> spokes(Chamber c) {
        List<Pt[]> out = new ArrayList<>();
        Pt mid = new Pt(c.x(), c.z());
        for (int d = 0; d < c.doors(); d++) out.add(new Pt[] {mid, c.doorPoint(d)});
        for (Socket s : plan.socketsOf(c.id())) {
            out.add(new Pt[] {mid, new Pt(s.x(), s.z())});
            // The way in front of the gate, out to a few blocks past it.
            out.add(new Pt[] {new Pt(s.x(), s.z()), new Pt(s.x() + dx(s.facing()) * 4, s.z() + dz(s.facing()) * 4)});
        }
        return out;
    }

    static int dx(char facing) {
        return facing == 'E' ? 1 : facing == 'W' ? -1 : 0;
    }

    static int dz(char facing) {
        return facing == 'S' ? 1 : facing == 'N' ? -1 : 0;
    }

    private void furnish() {
        for (Chamber c : plan.chambers()) {
            if (c.feature() == CaveLayout.Feature.NONE) continue;
            List<Pt[]> spokes = spokes(c);
            // Blobs sit between the spokes, a little off the middle.
            int blobs = c.feature() == CaveLayout.Feature.POOL ? 1 : 3 + c.radius() / 6;
            for (int b = 0; b < blobs; b++) {
                double angle = c.baseAngle() + Math.PI / c.doors() + b * 2 * Math.PI / Math.max(blobs, 1) * 0.5 + (hash(noiseSeeds[4], c.id(), b) % 100) / 100.0;
                double reach = c.radius() * (0.35 + (hash(noiseSeeds[4], b, c.id()) % 30) / 100.0);
                double bx = c.x() + Math.cos(angle) * reach, bz = c.z() + Math.sin(angle) * reach;
                double radius = c.feature() == CaveLayout.Feature.POOL ? 2.6 : 1.2;
                byte what = c.feature() == CaveLayout.Feature.POOL ? POOL : BLOCKED;
                for (int x = (int) Math.floor(bx - radius - 1); x <= bx + radius + 1; x++) {
                    for (int z = (int) Math.floor(bz - radius - 1); z <= bz + radius + 1; z++) {
                        if (!inBounds(x, z) || kind[at(x, z)] != WALK || Math.hypot(x - bx, z - bz) > radius) continue;
                        if (nearSpoke(x, z, spokes, 2.6) || nearEdge(x, z) || nearSocket(x, z, 9)) continue;
                        if (Math.hypot(x - c.x(), z - c.z()) > c.radius() - 3) continue;
                        kind[at(x, z)] = what;
                        if (what == POOL) {
                            floor[at(x, z)] = 0;
                        }
                    }
                }
            }
        }
    }

    private boolean nearSpoke(int x, int z, List<Pt[]> spokes, double within) {
        for (Pt[] s : spokes) if (distance(x, z, s[0], s[1]) <= within) return true;
        return false;
    }

    /** Within two blocks of a wall, so a pool or grove never narrows a way to a width nobody can pass. */
    private boolean nearEdge(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (kindAt(x + dx, z + dz) == SOLID) return true;
        return false;
    }

    // --- light ---

    private void light() {
        // A first scatter, one vine to roughly every five by five columns, then more wherever a block is still too dim.
        for (int gx = Math.floorDiv(minX, 5) * 5; gx < minX + width; gx += 5) {
            for (int gz = Math.floorDiv(minZ, 5) * 5; gz < minZ + depth; gz += 5) {
                int best = -1, bestHash = Integer.MAX_VALUE;
                for (int x = gx; x < gx + 5; x++) {
                    for (int z = gz; z < gz + 5; z++) {
                        if (!inBounds(x, z) || kind[at(x, z)] == SOLID) continue;
                        int h = hash(noiseSeeds[2], x, z);
                        if (h < bestHash && !nearSocket(x, z)) {
                            bestHash = h;
                            best = at(x, z);
                        }
                    }
                }
                if (best >= 0) hang(best);
            }
        }
        for (int round = 0; round < 12; round++) {
            byte[][] lit = lightLevels();
            boolean added = false;
            for (int x = minX; x < minX + width; x++) {
                for (int z = minZ; z < minZ + depth; z++) {
                    int i = at(x, z);
                    if (kind[i] != WALK) continue;
                    int f = floor[i];
                    if (level(lit, i, f) < MIN_LIGHT || level(lit, i, f + 1) < MIN_LIGHT) {
                        if (vineBottom[i] == 0) {
                            hang(i);
                            added = true;
                        }
                    }
                }
            }
            if (!added) return;
        }
    }

    private boolean nearSocket(int x, int z) {
        return nearSocket(x, z, 5);
    }

    /** Within this many blocks of any gate place: a gate needs open, dry, plant-free ground round it. */
    private boolean nearSocket(int x, int z, double within) {
        for (Socket s : plan.sockets()) if (Math.hypot(x - s.x(), z - s.z()) < within) return true;
        return false;
    }

    /** A vine from the ceiling down to three or four blocks above the floor, so it never hangs in anyone's way. */
    private void hang(int i) {
        int bottom = Math.max(floor[i] + 3, Math.min(top[i] - 6, floor[i] + 4));
        vineBottom[i] = (byte) Math.max(1, bottom);
    }

    private int heightOf() {
        int h = 0;
        for (int i = 0; i < top.length; i++) if (kind[i] != SOLID) h = Math.max(h, top[i]);
        return h + 2;
    }

    private int level(byte[][] lit, int column, int y) {
        return lit[column][Math.min(y, lit[column].length - 1)];
    }

    /** Light in every open block, spreading one less each block from the vines (which shine at 14), through air only. */
    byte[][] lightLevels() {
        byte[][] lit = new byte[width * depth][];
        for (int i = 0; i < lit.length; i++) lit[i] = new byte[kind[i] == SOLID ? 0 : top[i] + 1];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int i = 0; i < lit.length; i++) {
            if (kind[i] == SOLID || vineBottom[i] == 0) continue;
            for (int y = vineBottom[i]; y <= top[i]; y++) {
                if (y >= floor[i] && y < lit[i].length) {
                    lit[i][y] = VINE_LIGHT;
                    queue.add(new int[] {i, y});
                }
            }
        }
        int[][] dir = {{depth, 0}, {-depth, 0}, {1, 0}, {-1, 0}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int level = lit[c[0]][c[1]] - 1;
            if (level <= 0) continue;
            int x = c[0] / depth, z = c[0] % depth;
            List<int[]> next = new ArrayList<>(6);
            next.add(new int[] {c[0], c[1] + 1});
            next.add(new int[] {c[0], c[1] - 1});
            if (x + 1 < width) next.add(new int[] {c[0] + depth, c[1]});
            if (x > 0) next.add(new int[] {c[0] - depth, c[1]});
            if (z + 1 < depth) next.add(new int[] {c[0] + 1, c[1]});
            if (z > 0) next.add(new int[] {c[0] - 1, c[1]});
            for (int[] n : next) {
                if (n[0] < 0 || n[0] >= lit.length || kind[n[0]] == SOLID) continue;
                if (n[1] < floor[n[0]] || n[1] >= lit[n[0]].length) continue;
                if (lit[n[0]][n[1]] >= level) continue;
                lit[n[0]][n[1]] = (byte) level;
                queue.add(n);
            }
        }
        return lit;
    }

    /** The dimmest light level over every walkable block (feet and head), for tests and the build check. */
    int dimmest() {
        byte[][] lit = lightLevels();
        int low = 99;
        for (int x = minX; x < minX + width; x++) {
            for (int z = minZ; z < minZ + depth; z++) {
                int i = at(x, z);
                if (kind[i] != WALK) continue;
                low = Math.min(low, Math.min(level(lit, i, floor[i]), level(lit, i, floor[i] + 1)));
            }
        }
        return low;
    }

    // --- walking ---

    /**
     * Can a person walk from (x0, z0) to (x1, z1)? Four-way steps over walkable columns, floors at most one block
     * apart, two blocks of headroom. Returns every walkable column reached, as a set of keys, from the first one.
     */
    Set<Long> reachableFrom(int x0, int z0) {
        Set<Long> seen = new TreeSet<>();
        if (!walkable(x0, z0)) return seen;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {x0, z0});
        seen.add(key(x0, z0));
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] s : steps) {
                int x = c[0] + s[0], z = c[1] + s[1];
                if (!walkable(x, z) || seen.contains(key(x, z)) || !passable(c[0], c[1], x, z)) continue;
                seen.add(key(x, z));
                queue.add(new int[] {x, z});
            }
        }
        return seen;
    }

    /** One step between two neighbouring walkable columns. */
    boolean passable(int x0, int z0, int x1, int z1) {
        int a = floor[at(x0, z0)], b = floor[at(x1, z1)];
        if (Math.abs(a - b) > 1) return false;
        int feet = Math.max(a, b);
        return top[at(x0, z0)] >= feet + 1 && top[at(x1, z1)] >= feet + 1;
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** The column in front of a socket, two blocks out on the side people walk up to. */
    static int[] frontOf(Socket s) {
        return new int[] {s.x() + dx(s.facing()) * 2, s.z() + dz(s.facing()) * 2};
    }

    /**
     * Everything a person has to be able to reach from the start chamber's middle: every chamber's middle, every
     * tunnel's points and the front of every gate socket. Returns what can't be reached, in words. Empty is good.
     */
    List<String> unreachable() {
        List<String> problems = new ArrayList<>();
        Chamber start = plan.start();
        Set<Long> reach = reachableFrom(start.x(), start.z());
        if (reach.isEmpty()) return List.of("the start chamber's middle isn't walkable");
        for (Chamber c : plan.chambers()) {
            if (!reach.contains(key(c.x(), c.z()))) problems.add("chamber " + c.id() + " can't be reached");
            if (c.kind() != CaveLayout.Kind.START && c.feature() == CaveLayout.Feature.NONE) problems.add("chamber " + c.id() + " has no use");
        }
        for (Tunnel t : plan.tunnels()) {
            for (Pt p : CaveLayout.sample(t.points(), 3)) {
                long k = key((int) Math.round(p.x()), (int) Math.round(p.z()));
                if (!reach.contains(k)) problems.add("tunnel " + t.a() + "-" + t.b() + " breaks near " + Math.round(p.x()) + " " + Math.round(p.z()));
            }
        }
        for (Socket s : plan.sockets()) {
            int[] f = frontOf(s);
            if (!reach.contains(key(f[0], f[1]))) problems.add("socket " + s + " can't be reached");
            if (!reach.contains(key(s.x(), s.z()))) problems.add("socket " + s + " isn't on walkable ground");
            // The gate's footprint (two either side along its plane, one across) must be level, open and high.
            boolean alongX = s.facing() == 'N' || s.facing() == 'S';
            int level = floor[at(s.x(), s.z())];
            for (int a = -2; a <= 2; a++) {
                for (int b = -1; b <= 1; b++) {
                    int x = s.x() + (alongX ? a : b), z = s.z() + (alongX ? b : a);
                    if (!walkable(x, z) || floor[at(x, z)] != level || top[at(x, z)] < level + 8) problems.add("socket " + s + " has no room at " + x + " " + z);
                }
            }
        }
        // Columns the plan says people use: nothing walkable may be cut off from the start.
        int cut = 0;
        for (int x = minX; x < minX + width; x++) {
            for (int z = minZ; z < minZ + depth; z++) if (walkable(x, z) && !reach.contains(key(x, z))) cut++;
        }
        if (cut > 0) problems.add(cut + " walkable columns are cut off from the start");
        return problems;
    }

    @Override
    public String toString() {
        return "CaveShape" + Arrays.asList(plan.chambers().size(), plan.tunnels().size());
    }
}
