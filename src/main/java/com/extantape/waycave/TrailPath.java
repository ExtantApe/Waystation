package com.extantape.waycave;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The shape of a Waycave trail (K649, D194: "loose organic trails from the center to the Waygates"). Pure: block
 * columns on a flat floor, no server, so every rule is unit tested.
 *
 * <p>A trail follows the straight line between its ends with a gentle sideways wobble. The wobble is a sum of three
 * slow waves with phases from the seed, so it looks grown, is the same every time for the same seed, and fades to
 * nothing at both ends so the trail meets its start and its gate squarely. It's at most a sixth of the trail's length
 * and never more than {@link #MAX_WOBBLE} blocks. If the wobble would step off the floor (a wall, a pool), the wobble
 * is halved until it fits; if even the straight line doesn't fit, there's no trail.
 *
 * <p>The result is 4-connected: every block touches the next by a side, so the trail reads as one worn track.
 */
public final class TrailPath {
    static final double MAX_WOBBLE = 4.0;
    private static final int TRIES = 5;

    /** A floor block column. */
    public record Cell(int x, int z) {}

    private TrailPath() {}

    /**
     * The trail through these points in order, each leg with its own wobble. Legs share their seed only through the
     * caller, so two trails that pass the same first point with the same first-leg seed share that stretch.
     *
     * @param seeds one per leg (points.size() - 1)
     * @return the cells in walking order, or an empty list when any leg can't be laid
     */
    public static List<Cell> route(List<Cell> points, long[] seeds, BiPredicate<Integer, Integer> walkable) {
        if (points.size() < 2 || seeds.length != points.size() - 1) throw new IllegalArgumentException("need one seed per leg");
        LinkedHashSet<Cell> out = new LinkedHashSet<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            List<Cell> leg = leg(points.get(i), points.get(i + 1), seeds[i], walkable);
            if (leg.isEmpty()) return List.of();
            out.addAll(leg);
        }
        return List.copyOf(out);
    }

    /** One leg from a to b. */
    public static List<Cell> leg(Cell a, Cell b, long seed, BiPredicate<Integer, Integer> walkable) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double length = Math.hypot(dx, dz);
        if (length == 0) return walkable.test(a.x(), a.z()) ? List.of(a) : List.of();
        double amplitude = Math.min(MAX_WOBBLE, length / 6.0);
        Random r = new Random(seed);
        double[] phase = {r.nextDouble() * Math.PI * 2, r.nextDouble() * Math.PI * 2, r.nextDouble() * Math.PI * 2};
        double sign = r.nextBoolean() ? 1 : -1;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            List<Cell> cells = trace(a, b, length, amplitude * sign, phase);
            if (cells.stream().allMatch(c -> walkable.test(c.x(), c.z()))) return cells;
            amplitude /= 2;
        }
        List<Cell> straight = trace(a, b, length, 0, phase);
        return straight.stream().allMatch(c -> walkable.test(c.x(), c.z())) ? straight : List.of();
    }

    /** Sideways offset at t (0 to 1): three slow waves, faded to zero at both ends. */
    static double wobble(double t, double amplitude, double[] phase) {
        double w = 0.6 * Math.sin(Math.PI * 1.0 * t + phase[0]) + 0.3 * Math.sin(Math.PI * 2.3 * t + phase[1]) + 0.1 * Math.sin(Math.PI * 4.1 * t + phase[2]);
        double fade = Math.sin(Math.PI * t); // 0 at both ends
        return amplitude * w * fade;
    }

    private static List<Cell> trace(Cell a, Cell b, double length, double amplitude, double[] phase) {
        double ux = (b.x() - a.x()) / length, uz = (b.z() - a.z()) / length; // along
        double px = -uz, pz = ux; // sideways
        int samples = (int) Math.ceil(length * 4);
        List<Cell> cells = new ArrayList<>();
        Set<Cell> seen = new LinkedHashSet<>();
        Cell last = null;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double off = wobble(t, amplitude, phase);
            double x = a.x() + (b.x() - a.x()) * t + px * off, z = a.z() + (b.z() - a.z()) * t + pz * off;
            Cell c = i == 0 ? a : i == samples ? b : new Cell((int) Math.round(x), (int) Math.round(z));
            if (last != null && !c.equals(last)) {
                // Diagonal step: add the corner so each block touches the next by a side.
                if (c.x() != last.x() && c.z() != last.z()) add(new Cell(c.x(), last.z()), cells, seen);
                // A jump of more than one block (only with tight wobble on short legs): fill straight across.
                while (Math.abs(c.x() - last.x()) > 1 || Math.abs(c.z() - last.z()) > 1) {
                    last = new Cell(last.x() + Integer.signum(c.x() - last.x()), last.z() + Integer.signum(c.z() - last.z()));
                    add(last, cells, seen);
                }
            }
            add(c, cells, seen);
            last = c;
        }
        return cells;
    }

    /**
     * Adds a block unless it's the one just added. A trail that curls back over a block it already crossed keeps it
     * twice, so every block still touches the next by a side.
     */
    private static void add(Cell c, List<Cell> cells, Set<Cell> seen) {
        seen.add(c);
        if (cells.isEmpty() || !cells.getLast().equals(c)) cells.add(c);
    }

    /**
     * The edging beside a trail (glow lichen in the design): floor cells that touch the trail by a side and aren't on
     * it, kept where {@code keep} says (so it can be sparse and seeded).
     */
    public static List<Cell> edging(List<Cell> trail, BiPredicate<Integer, Integer> walkable, BiPredicate<Integer, Integer> keep) {
        Set<Cell> on = new LinkedHashSet<>(trail);
        LinkedHashSet<Cell> out = new LinkedHashSet<>();
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Cell c : trail)
            for (int[] s : sides) {
                Cell n = new Cell(c.x() + s[0], c.z() + s[1]);
                if (!on.contains(n) && walkable.test(n.x(), n.z()) && keep.test(n.x(), n.z())) out.add(n);
            }
        return List.copyOf(out);
    }
}
