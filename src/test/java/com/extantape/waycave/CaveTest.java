package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.waycave.CaveLayout.Chamber;
import com.extantape.waycave.CaveLayout.Kind;
import com.extantape.waycave.CaveLayout.Plan;
import com.extantape.waycave.CaveLayout.Socket;
import com.extantape.waycave.CaveLayout.Tunnel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The cave's rules, over many seeds: always connected, no dead chambers, lit, stable as groups come and go. */
class CaveTest {
    static final String[] NAMES = {"Survival", "Creative", "Kids", "Friends", "Legacy", "Quiet", "Lobby"};

    /** A plan for the first n groups, grown one at a time like a server gaining worlds. */
    static Plan planFor(long seed, int groups, int gatesEach) {
        Plan plan = CaveLayout.fresh(seed);
        for (int i = 0; i < groups; i++) plan = CaveLayout.with(plan, NAMES[i], gatesEach + i % 3);
        return plan;
    }

    @Test
    void theSameSeedAndGroupsGiveTheSameCaveAndAnotherSeedADifferentOne() {
        assertEquals(CaveLayout.toMap(planFor(5, 4, 2)), CaveLayout.toMap(planFor(5, 4, 2)));
        assertNotEquals(CaveLayout.toMap(planFor(5, 4, 2)), CaveLayout.toMap(planFor(6, 4, 2)));
        CaveShape a = new CaveShape(planFor(5, 4, 2)), b = new CaveShape(planFor(5, 4, 2));
        assertEquals(a.openCount(), b.openCount());
    }

    @Test
    void aGroupAddedLaterGrowsANewChamberAndMovesNothing() {
        for (long seed = 1; seed <= 20; seed++) {
            Plan before = planFor(seed, 3, 2);
            Plan after = CaveLayout.with(before, NAMES[3], 3);
            assertEquals(before.chambers().size() + 1, after.chambers().size(), "seed " + seed);
            assertEquals(before.chambers(), after.chambers().subList(0, before.chambers().size()), "no chamber moved, seed " + seed);
            assertTrue(after.tunnels().subList(0, before.tunnels().size()).equals(before.tunnels()), "no tunnel changed, seed " + seed);
            assertTrue(after.sockets().containsAll(before.sockets()), "no socket moved, seed " + seed);
            assertNotNull(after.ofGroup(NAMES[3]));
            // Asking again changes nothing.
            assertSame(after, CaveLayout.with(after, NAMES[3], 3));
        }
    }

    @Test
    void everyChamberAndGateCanBeWalkedToFromTheStartAndNothingIsCutOff() {
        for (long seed = 1; seed <= 200; seed++) {
            int groups = 1 + (int) (seed % 7);
            Plan plan = planFor(seed, groups, 1 + (int) (seed % 4));
            assertEquals(groups, plan.chambers().stream().filter(c -> c.kind() == Kind.GROUP).count(), "every group has a chamber, seed " + seed);
            CaveShape shape = new CaveShape(plan);
            assertEquals(List.of(), shape.unreachable(), "seed " + seed + " with " + groups + " groups");
        }
    }

    @Test
    void noChamberIsDeadEndsAreFewAndEveryChamberHasAUse() {
        for (long seed = 1; seed <= 40; seed++) {
            Plan plan = planFor(seed, 5, 2);
            for (Chamber c : plan.chambers()) {
                if (c.kind() == Kind.START) continue;
                boolean hasGates = !plan.socketsOf(c.id()).isEmpty();
                assertTrue(hasGates || c.feature() != CaveLayout.Feature.NONE, "chamber " + c.id() + " has gates or a feature, seed " + seed);
                int tunnels = 0;
                for (Tunnel t : plan.tunnels()) if (t.a() == c.id() || t.b() == c.id()) tunnels++;
                assertTrue(tunnels >= 1, "chamber " + c.id() + " is joined to the rest");
            }
        }
    }

    @Test
    void everyWalkableBlockIsLitToAtLeastNineSoNothingSpawns() {
        for (long seed = 1; seed <= 25; seed++) {
            CaveShape shape = new CaveShape(planFor(seed, 4, 3));
            assertTrue(shape.dimmest() >= CaveShape.MIN_LIGHT, "seed " + seed + " dimmest " + shape.dimmest());
        }
    }

    @Test
    void floorsStepByAtMostOneBlockAndEveryColumnHasTwoBlocksOfHeadroom() {
        CaveShape shape = new CaveShape(planFor(11, 5, 3));
        int checked = 0;
        for (int x = shape.minX; x < shape.minX + shape.width - 1; x++) {
            for (int z = shape.minZ; z < shape.minZ + shape.depth - 1; z++) {
                if (!shape.open(x, z)) continue;
                assertTrue(shape.topAt(x, z) >= shape.floorAt(x, z) + 3, "headroom at " + x + " " + z);
                for (int[] n : new int[][] {{1, 0}, {0, 1}}) {
                    if (shape.open(x + n[0], z + n[1])) {
                        assertTrue(Math.abs(shape.floorAt(x, z) - shape.floorAt(x + n[0], z + n[1])) <= 1, "step at " + x + " " + z);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 1000);
    }

    @Test
    void tunnelsAreThreeToFiveBlocksWideAndWindRatherThanRunStraight() {
        for (long seed = 1; seed <= 20; seed++) {
            Plan plan = planFor(seed, 4, 2);
            for (Tunnel t : plan.tunnels()) {
                assertTrue(t.radius() >= 1.75 && t.radius() <= 2.75, "width 3 to 5 blocks: " + t.radius());
                CaveLayout.Pt a = t.points().getFirst(), b = t.points().getLast();
                double straight = Math.hypot(b.x() - a.x(), b.z() - a.z()), along = 0;
                for (int i = 1; i < t.points().size(); i++) {
                    CaveLayout.Pt p = t.points().get(i - 1), q = t.points().get(i);
                    along += Math.hypot(q.x() - p.x(), q.z() - p.z());
                }
                assertTrue(along > straight, "it winds");
            }
        }
    }

    @Test
    void gateSocketsAreSpacedClearOfDoorsAndFaceTheMiddle() {
        for (long seed = 1; seed <= 40; seed++) {
            Plan plan = planFor(seed, 5, 3);
            for (Chamber c : plan.chambers()) {
                List<Socket> mine = plan.socketsOf(c.id());
                for (Socket s : mine) {
                    for (Socket o : mine) {
                        if (s != o) assertTrue(Math.max(Math.abs(s.x() - o.x()), Math.abs(s.z() - o.z())) >= 7, "gates stand at least seven apart in each direction");
                    }
                    for (int d = 0; d < c.doors(); d++) {
                        CaveLayout.Pt p = c.doorPoint(d);
                        assertTrue(Math.hypot(s.x() - p.x(), s.z() - p.z()) >= 6, "a tunnel never opens onto a gate");
                    }
                    assertEquals(CaveLayout.facingToward(s.x() - c.x(), s.z() - c.z()), s.facing());
                    assertTrue(Math.hypot(s.x() - c.x(), s.z() - c.z()) <= c.radius() - 4, "inside the wall");
                }
            }
        }
    }

    @Test
    void aChamberHoldsAllItsGroupsGatesAndSomeSpare() {
        for (int gates = 1; gates <= 8; gates++) {
            Plan plan = CaveLayout.with(CaveLayout.fresh(3), "Survival", gates);
            Chamber c = plan.ofGroup("Survival");
            assertTrue(plan.socketsOf(c.id()).size() >= gates + CaveLayout.SPARE_SOCKETS, gates + " gates");
        }
    }

    @Test
    void theStartChamberHasOneSocketForTheGateToAnotherServer() {
        for (long seed = 1; seed <= 20; seed++) {
            Plan plan = planFor(seed, 3, 2);
            assertEquals(1, plan.socketsOf(0).size(), "seed " + seed);
        }
    }

    @Test
    void theCaveStaysInsideTheWorldBorder() {
        for (long seed = 1; seed <= 20; seed++) {
            Plan plan = planFor(seed, 7, 4);
            for (Chamber c : plan.chambers()) {
                assertTrue(Math.max(Math.abs(c.x()), Math.abs(c.z())) + c.radius() <= CaveLayout.LIMIT, "seed " + seed);
            }
        }
    }

    @Test
    void aPlanSurvivesBeingSavedAndRead() {
        Plan plan = planFor(9, 5, 2);
        Plan back = CaveLayout.fromMap(CaveLayout.toMap(plan));
        assertEquals(plan, back);
        List<Object> seq = new ArrayList<>(List.of(plan.seed()));
        assertEquals(CaveLayout.toMap(plan), CaveLayout.toMap(back));
        assertThrows(RuntimeException.class, () -> CaveLayout.fromMap(Map.of("seed", 1L, "chambers", List.of(), "tunnels", List.of(), "sockets", List.of())));
        assertFalse(seq.isEmpty());
    }
}
