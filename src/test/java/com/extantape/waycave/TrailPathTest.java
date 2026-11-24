package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.waycave.TrailPath.Cell;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class TrailPathTest {
    static final BiPredicate<Integer, Integer> OPEN = (x, z) -> true;

    static void connected(List<Cell> t) {
        for (int i = 1; i < t.size(); i++) {
            Cell a = t.get(i - 1), b = t.get(i);
            assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), "each block touches the next by a side: " + a + " " + b);
        }
    }

    @Test
    void aTrailStartsAndEndsExactlyAndIsOneTrack() {
        List<Cell> t = TrailPath.leg(new Cell(0, 0), new Cell(30, 10), 42, OPEN);
        assertEquals(new Cell(0, 0), t.getFirst());
        assertEquals(new Cell(30, 10), t.getLast());
        connected(t);
        assertEquals(t.size(), new HashSet<>(t).size(), "no block twice");
    }

    @Test
    void theSameSeedGivesTheSameTrailAndAnotherSeedADifferentOne() {
        List<Cell> a = TrailPath.leg(new Cell(0, 0), new Cell(40, 0), 7, OPEN);
        assertEquals(a, TrailPath.leg(new Cell(0, 0), new Cell(40, 0), 7, OPEN));
        assertNotEquals(a, TrailPath.leg(new Cell(0, 0), new Cell(40, 0), 8, OPEN));
    }

    @Test
    void itWandersButStaysNearTheLine() {
        boolean wandered = false;
        for (long seed = 0; seed < 50; seed++) {
            for (Cell c : TrailPath.leg(new Cell(0, 0), new Cell(60, 0), seed, OPEN)) {
                assertTrue(Math.abs(c.z()) <= TrailPath.MAX_WOBBLE + 1, "seed " + seed + " strays to " + c);
                if (c.z() != 0) wandered = true;
            }
        }
        assertTrue(wandered, "a long trail isn't a ruler line");
    }

    @Test
    void aShortTrailWobblesLess() {
        for (long seed = 0; seed < 50; seed++)
            for (Cell c : TrailPath.leg(new Cell(0, 0), new Cell(6, 0), seed, OPEN)) assertTrue(Math.abs(c.z()) <= 1, "seed " + seed + ": " + c);
    }

    @Test
    void itBendsAwayFromAWallOrGoesStraight() {
        // A corridor 3 wide: z from -1 to 1.
        BiPredicate<Integer, Integer> corridor = (x, z) -> Math.abs(z) <= 1;
        for (long seed = 0; seed < 50; seed++) {
            List<Cell> t = TrailPath.leg(new Cell(0, 0), new Cell(50, 0), seed, corridor);
            assertFalse(t.isEmpty());
            assertTrue(t.stream().allMatch(c -> corridor.test(c.x(), c.z())), "seed " + seed + " leaves the floor");
            connected(t);
        }
    }

    @Test
    void noFloorMeansNoTrail() {
        BiPredicate<Integer, Integer> wallAt20 = (x, z) -> x != 20;
        assertTrue(TrailPath.leg(new Cell(0, 0), new Cell(40, 0), 1, wallAt20).isEmpty(), "a wall right across: nothing, never a trail through stone");
    }

    @Test
    void trailsThroughTheSameDoorShareTheirFirstStretch() {
        Cell center = new Cell(0, 0), door = new Cell(20, 0);
        List<Cell> toA = TrailPath.route(List.of(center, door, new Cell(30, 8)), new long[] {99, 1}, OPEN);
        List<Cell> toB = TrailPath.route(List.of(center, door, new Cell(30, -8)), new long[] {99, 2}, OPEN);
        List<Cell> first = TrailPath.leg(center, door, 99, OPEN);
        assertEquals(first, toA.subList(0, first.size()));
        assertEquals(first, toB.subList(0, first.size()));
        assertNotEquals(toA.getLast(), toB.getLast());
        connected(toA);
    }

    @Test
    void aRouteWithABlockedLegHasNoTrailAtAll() {
        BiPredicate<Integer, Integer> blocked = (x, z) -> !(x == 25 && Math.abs(z) < 50);
        assertTrue(TrailPath.route(List.of(new Cell(0, 0), new Cell(20, 0), new Cell(30, 0)), new long[] {1, 2}, blocked).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TrailPath.route(List.of(new Cell(0, 0), new Cell(1, 0)), new long[] {}, OPEN));
    }

    @Test
    void edgingTouchesTheTrailAndIsNeverOnIt() {
        List<Cell> t = TrailPath.leg(new Cell(0, 0), new Cell(20, 0), 3, OPEN);
        Set<Cell> on = new HashSet<>(t);
        List<Cell> e = TrailPath.edging(t, OPEN, (x, z) -> true);
        assertFalse(e.isEmpty());
        for (Cell c : e) {
            assertFalse(on.contains(c));
            assertTrue(on.contains(new Cell(c.x() + 1, c.z())) || on.contains(new Cell(c.x() - 1, c.z()))
                || on.contains(new Cell(c.x(), c.z() + 1)) || on.contains(new Cell(c.x(), c.z() - 1)));
        }
        assertTrue(TrailPath.edging(t, (x, z) -> false, (x, z) -> true).isEmpty(), "no edging off the floor");
    }

    @Test
    void aTrailToWhereYouStandIsOneBlock() {
        assertEquals(List.of(new Cell(3, 3)), TrailPath.leg(new Cell(3, 3), new Cell(3, 3), 0, OPEN));
    }
}
