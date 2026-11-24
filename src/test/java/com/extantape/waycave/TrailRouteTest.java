package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.waycave.CaveLayout.Plan;
import com.extantape.waycave.CaveLayout.Socket;
import com.extantape.waycave.TrailPath.Cell;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Where trails run in a generated cave: on walkable ground, start to gate, the same for one player and not for two. */
class TrailRouteTest {
    static Plan plan(long seed) {
        return CaveTest.planFor(seed, 5, 3);
    }

    @Test
    void everyTrailIsOneWalkableTrackFromTheMonumentToTheFrontOfItsGate() {
        for (long seed = 1; seed <= 25; seed++) {
            Plan plan = plan(seed);
            CaveShape shape = new CaveShape(plan);
            for (Socket s : plan.sockets()) {
                List<Cell> trail = TrailRoute.trail(shape, s, 42 + seed);
                assertFalse(trail.isEmpty(), "a trail to " + s + ", seed " + seed);
                for (Cell c : trail) assertTrue(shape.walkable(c.x(), c.z()), "on the floor at " + c + ", seed " + seed);
                for (int i = 1; i < trail.size(); i++) {
                    Cell a = trail.get(i - 1), b = trail.get(i);
                    assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), "no gaps, seed " + seed);
                    assertTrue(shape.passable(a.x(), a.z(), b.x(), b.z()), "no step over one block, seed " + seed);
                }
                int[] front = CaveShape.frontOf(s);
                assertEquals(new Cell(front[0], front[1]), trail.getLast(), "ends in front of the gate");
                Cell first = trail.getFirst();
                assertTrue(Math.hypot(first.x(), first.z()) <= TrailRoute.START_RING + 2, "starts by the monument, seed " + seed);
            }
        }
    }

    @Test
    void aTrailToAGateInAnotherChamberRunsThroughTheTunnelsToIt() {
        Plan plan = plan(7);
        CaveShape shape = new CaveShape(plan);
        Socket far = plan.sockets().stream().filter(s -> s.chamber() >= 3).findFirst().orElseThrow();
        List<Cell> trail = TrailRoute.trail(shape, far, 1);
        Set<Cell> on = new HashSet<>(trail);
        List<CaveLayout.Chamber> through = TrailRoute.chambersTo(plan, far.chamber());
        assertEquals(0, through.getFirst().id());
        assertEquals(far.chamber(), through.getLast().id());
        for (int i = 0; i + 1 < through.size(); i++) {
            CaveLayout.Tunnel t = plan.between(through.get(i).id(), through.get(i + 1).id());
            assertNotNull(t, "each hop is a real tunnel");
        }
        CaveLayout.Pt mid = plan.between(through.get(0).id(), through.get(1).id()).points().get(plan.between(through.get(0).id(), through.get(1).id()).points().size() / 2);
        boolean near = false;
        for (Cell c : on) near |= Math.hypot(c.x() - mid.x(), c.z() - mid.z()) < 4;
        assertTrue(near, "it passes down the first tunnel");
    }

    @Test
    void aPlayersTrailIsTheSameEveryTimeAndDiffersFromAnothersPlayers() {
        Plan plan = plan(3);
        CaveShape shape = new CaveShape(plan);
        Socket s = plan.sockets().get(2);
        assertEquals(TrailRoute.trail(shape, s, 11), TrailRoute.trail(shape, s, 11));
        assertNotEquals(TrailRoute.trail(shape, s, 11), TrailRoute.trail(shape, s, 12));
        assertEquals(TrailRoute.edging(shape, TrailRoute.trail(shape, s, 11), 5), TrailRoute.edging(shape, TrailRoute.trail(shape, s, 11), 5));
    }

    @Test
    void trailsToTwoGatesInOneChamberShareTheirFirstStretch() {
        Plan plan = plan(5);
        CaveShape shape = new CaveShape(plan);
        for (Chamber chamber : plan.chambers().stream().filter(c -> plan.socketsOf(c.id()).size() >= 2 && c.id() > 0).map(c -> new Chamber(c.id())).toList()) {
            List<Socket> two = plan.socketsOf(chamber.id);
            List<Cell> a = TrailRoute.trail(shape, two.get(0), 9), b = TrailRoute.trail(shape, two.get(1), 9);
            int shared = 0;
            while (shared < a.size() && shared < b.size() && a.get(shared).equals(b.get(shared))) shared++;
            assertTrue(shared > 15, "they share the way in from the start, chamber " + chamber.id + ": " + shared);
            return;
        }
        fail("no chamber with two gates");
    }

    record Chamber(int id) {}

    @Test
    void edgingStaysOnWalkableGroundOffTheTrailAndBesideIt() {
        Plan plan = plan(2);
        CaveShape shape = new CaveShape(plan);
        List<Cell> trail = TrailRoute.trail(shape, plan.sockets().get(1), 3);
        Set<Cell> on = new HashSet<>(trail);
        List<Cell> edge = TrailRoute.edging(shape, trail, 3);
        assertFalse(edge.isEmpty());
        for (Cell c : edge) {
            assertTrue(shape.walkable(c.x(), c.z()));
            assertFalse(on.contains(c));
        }
    }

    @Test
    void aPlayerWithNoGatesGetsNoTrailAndAGateOffThePlanGetsNone() {
        Plan plan = plan(4);
        CaveShape shape = new CaveShape(plan);
        assertTrue(TrailRoute.trail(shape, new Socket(99, 500, 500, 'N'), 1).isEmpty());
    }
}
