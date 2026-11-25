package com.extantape.waycave;

import com.extantape.waycave.CaveLayout.Chamber;
import com.extantape.waycave.CaveLayout.Plan;
import com.extantape.waycave.CaveLayout.Pt;
import com.extantape.waycave.CaveLayout.Socket;
import com.extantape.waycave.CaveLayout.Tunnel;
import com.extantape.waycave.TrailPath.Cell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Where a trail runs, from the start chamber to one gate. Pure, so every rule is tested without a server.
 *
 * <p>A trail follows the plan: out from the monument, down the winding middle of each tunnel on the way, across each
 * chamber, and up to the front of the gate. Each stretch between two such points gets its own gentle wobble from
 * {@link TrailPath}, seeded by the player and the two points. So trails differ a little between players, stay the same
 * for one player every visit, and two trails to gates in one chamber share everything up to that chamber's middle.
 * Every block of a trail is walkable ground: a stretch that would leave it is straightened, and the plan's own checks
 * (see {@link CaveShape#unreachable()}) guarantee a straight stretch always fits.
 */
final class TrailRoute {
    /** How far from the monument's middle a trail begins. */
    static final int START_RING = 5;

    private TrailRoute() {}

    /** The chambers to pass through from the start to this one, by the shortest way along the tunnels. Empty if none. */
    static List<Chamber> chambersTo(Plan plan, int target) {
        Map<Integer, Double> best = new HashMap<>();
        Map<Integer, Integer> from = new HashMap<>();
        PriorityQueue<double[]> queue = new PriorityQueue<>(Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1]));
        best.put(0, 0.0);
        queue.add(new double[] {0, 0});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int at = (int) top[1];
            if (top[0] > best.getOrDefault(at, Double.MAX_VALUE)) continue;
            if (at == target) break;
            for (Tunnel t : plan.tunnels()) {
                int other = t.a() == at ? t.b() : t.b() == at ? t.a() : -1;
                if (other < 0) continue;
                double cost = top[0] + length(t);
                if (cost < best.getOrDefault(other, Double.MAX_VALUE)) {
                    best.put(other, cost);
                    from.put(other, at);
                    queue.add(new double[] {cost, other});
                }
            }
        }
        if (!best.containsKey(target)) return List.of();
        List<Chamber> path = new ArrayList<>();
        for (int at = target; ; at = from.get(at)) {
            path.addFirst(plan.chamber(at));
            if (at == 0) break;
        }
        return path;
    }

    private static double length(Tunnel t) {
        double len = 0;
        for (int i = 1; i < t.points().size(); i++) {
            len += Math.hypot(t.points().get(i).x() - t.points().get(i - 1).x(), t.points().get(i).z() - t.points().get(i - 1).z());
        }
        return len;
    }

    /** The points a trail to this socket passes through, in order, before any wobble. Empty if there's no way. */
    static List<Cell> waypoints(Plan plan, Socket socket) {
        List<Chamber> chambers = chambersTo(plan, socket.chamber());
        if (chambers.isEmpty()) return List.of();
        List<Pt> raw = new ArrayList<>();
        Chamber start = plan.start();
        raw.add(new Pt(start.x(), start.z()));
        for (int i = 0; i + 1 < chambers.size(); i++) {
            Chamber a = chambers.get(i), b = chambers.get(i + 1);
            Tunnel t = plan.between(a.id(), b.id());
            List<Pt> pts = new ArrayList<>(t.points());
            if (t.a() != a.id()) java.util.Collections.reverse(pts);
            raw.addAll(pts);
            raw.add(new Pt(b.x(), b.z()));
        }
        int[] front = CaveShape.frontOf(socket);
        raw.add(new Pt(front[0], front[1]));
        List<Cell> cells = new ArrayList<>();
        for (Pt p : raw) {
            Cell c = new Cell((int) Math.round(p.x()), (int) Math.round(p.z()));
            if (cells.isEmpty() || !cells.getLast().equals(c)) cells.add(c);
        }
        // The first point is the start chamber's middle, which the monument stands on: begin a few blocks out along the way.
        Cell mid = cells.getFirst();
        if (cells.size() > 1) {
            Cell next = cells.get(1);
            double dx = next.x() - mid.x(), dz = next.z() - mid.z(), len = Math.hypot(dx, dz);
            if (len > START_RING + 1) {
                cells.set(0, new Cell(mid.x() + (int) Math.round(dx / len * START_RING), mid.z() + (int) Math.round(dz / len * START_RING)));
            } else {
                cells.removeFirst();
            }
        }
        return cells;
    }

    /**
     * The trail cells, in walking order, for a player's seed. Empty when the gate isn't on the plan or no trail fits.
     * Cells are block columns; the caller puts them at each column's floor.
     */
    static List<Cell> trail(CaveShape shape, Socket socket, long playerSeed) {
        List<Cell> points = waypoints(shape.plan, socket);
        if (points.size() < 2) return List.of();
        long[] seeds = new long[points.size() - 1];
        for (int i = 0; i < seeds.length; i++) seeds[i] = mix(playerSeed, points.get(i), points.get(i + 1));
        // Stretch by stretch, in order. A trail may cross itself (down a tunnel and back through a chamber's middle), and
        // it still has to read as one track, so a block seen twice is kept twice and only a repeat in a row is dropped.
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            List<Cell> leg = TrailPath.leg(points.get(i), points.get(i + 1), seeds[i], shape::walkable);
            if (leg.isEmpty()) return List.of();
            for (Cell c : leg) if (out.isEmpty() || !out.getLast().equals(c)) out.add(c);
        }
        return out;
    }

    /** The glow lichen beside a trail: a seeded, sparse edging on walkable ground. */
    static List<Cell> edging(CaveShape shape, List<Cell> trail, long playerSeed) {
        return TrailPath.edging(trail, shape::walkable, (x, z) -> {
            long h = playerSeed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
            h ^= h >>> 31;
            h *= 0xff51afd7ed558ccdL;
            return Math.floorMod(h >>> 20, 100L) < 38;
        });
    }

    private static long mix(long seed, Cell a, Cell b) {
        long h = seed;
        for (int v : new int[] {a.x(), a.z(), b.x(), b.z()}) {
            h ^= v * 0x9E3779B97F4A7C15L;
            h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
        }
        return h;
    }
}
