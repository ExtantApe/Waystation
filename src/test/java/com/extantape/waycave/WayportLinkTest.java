package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The gate to another server fails closed (D301, kids first): only a Wayport API that is new enough, has the calls
 * version 1 promises and gives a plain yes can show or open it. The stand-ins below are shaped like Wayport's API.
 */
class WayportLinkTest {
    enum Result { STARTED, ALREADY_COUNTING, NO_PERMISSION, NOT_SET_UP, CLOSED, SOMETHING_NEW }

    public interface Good {
        int apiVersion();
        boolean mayUse(Player player);
        Result send(Player player);
    }

    static Good good(int version, boolean yes, Result result) {
        return new Good() {
            @Override public int apiVersion() { return version; }
            @Override public boolean mayUse(Player player) { return yes; }
            @Override public Result send(Player player) { return result; }
        };
    }

    @Test
    void aVersionOneApiThatSaysYesOpensTheGate() {
        WayportLink.Api api = WayportLink.Api.bind(Good.class, good(1, true, Result.STARTED));
        assertNotNull(api);
        assertTrue(api.mayUse(null));
        assertTrue(api.send(null));
    }

    @Test
    void aNewerApiStillWorks() {
        assertNotNull(WayportLink.Api.bind(Good.class, good(2, true, Result.STARTED)));
    }

    @Test
    void wayportsNoIsNo() {
        WayportLink.Api api = WayportLink.Api.bind(Good.class, good(1, false, Result.NO_PERMISSION));
        assertNotNull(api);
        assertFalse(api.mayUse(null));
        assertFalse(api.send(null));
    }

    @Test
    void anApiOlderThanTheOneNeededIsNoApi() {
        assertNull(WayportLink.Api.bind(Good.class, good(0, true, Result.STARTED)));
        assertNull(WayportLink.Api.bind(Good.class, good(-1, true, Result.STARTED)));
    }

    @Test
    void noServiceIsNoApi() {
        assertNull(WayportLink.Api.bind(null, null));
        assertNull(WayportLink.Api.bind(Good.class, null));
        assertNull(WayportLink.Api.bind(Good.class, "not a Wayport"), "a provider that isn't the service's type");
    }

    public interface NoVersion {
        boolean mayUse(Player player);
        Result send(Player player);
    }

    public interface NoSend {
        int apiVersion();
        boolean mayUse(Player player);
    }

    public interface MaybeUse {
        int apiVersion();
        Boolean mayUse(Player player);
        Result send(Player player);
    }

    public interface TextVersion {
        String apiVersion();
        boolean mayUse(Player player);
        Result send(Player player);
    }

    @Test
    void anApiOfAnotherShapeIsNoApi() {
        assertNull(WayportLink.Api.bind(NoVersion.class, new NoVersion() {
            @Override public boolean mayUse(Player player) { return true; }
            @Override public Result send(Player player) { return Result.STARTED; }
        }));
        assertNull(WayportLink.Api.bind(NoSend.class, new NoSend() {
            @Override public int apiVersion() { return 1; }
            @Override public boolean mayUse(Player player) { return true; }
        }));
        assertNull(WayportLink.Api.bind(MaybeUse.class, new MaybeUse() {
            @Override public int apiVersion() { return 1; }
            @Override public Boolean mayUse(Player player) { return null; }
            @Override public Result send(Player player) { return Result.STARTED; }
        }), "mayUse must be a plain yes or no");
        assertNull(WayportLink.Api.bind(TextVersion.class, new TextVersion() {
            @Override public String apiVersion() { return "1"; }
            @Override public boolean mayUse(Player player) { return true; }
            @Override public Result send(Player player) { return Result.STARTED; }
        }));
    }

    @Test
    void anApiThatThrowsIsNo() {
        Good broken = new Good() {
            @Override public int apiVersion() { return 1; }
            @Override public boolean mayUse(Player player) { throw new IllegalStateException("off the main thread"); }
            @Override public Result send(Player player) { throw new IllegalStateException("off the main thread"); }
        };
        WayportLink.Api api = WayportLink.Api.bind(Good.class, broken);
        assertNotNull(api);
        assertFalse(api.mayUse(null));
        assertFalse(api.send(null));
        assertNull(WayportLink.Api.bind(Good.class, new Good() {
            @Override public int apiVersion() { throw new IllegalStateException(); }
            @Override public boolean mayUse(Player player) { return true; }
            @Override public Result send(Player player) { return Result.STARTED; }
        }));
    }

    @Test
    void onlyACountdownThatIsRunningCountsAsSent() {
        assertTrue(WayportLink.started(Result.STARTED));
        assertTrue(WayportLink.started(Result.ALREADY_COUNTING));
        assertFalse(WayportLink.started(Result.NO_PERMISSION));
        assertFalse(WayportLink.started(Result.NOT_SET_UP));
        assertFalse(WayportLink.started(Result.CLOSED));
        assertFalse(WayportLink.started(Result.SOMETHING_NEW), "an answer added later is a refusal until Waycave learns it");
        assertFalse(WayportLink.started(null));
        assertFalse(WayportLink.started("STARTED"));
        assertFalse(WayportLink.started(true));
    }

    @Test
    void waycaveNamesTheServiceAndVersionWayportShips() {
        assertEquals("Wayport", WayportLink.PLUGIN);
        assertEquals("com.extantape.wayport.api.WayportApi", WayportLink.SERVICE);
        assertEquals(1, WayportLink.NEEDS_VERSION);
    }

    @Test
    void aVisitReachesAStepPastTheGateAndNoFurther() {
        // The spot the trails end at, two blocks in front of a gate, must stay outside: arriving there starts nothing.
        assertTrue(WayportWalkIn.LINGER > 0 && 0.5 + WayportWalkIn.LINGER < 2.0 - 0.5);
        assertTrue(WayportWalkIn.LINGER <= Travel.STEP_CLEAR);
    }

    @Test
    void walkingThroughTheGateNeverCountsAsStopping() {
        WayportWalkIn.Still still = new WayportWalkIn.Still();
        for (int i = 0; i < 40; i++) assertFalse(still.look(i * 0.2, 64, 0), "step " + i);
    }

    @Test
    void stoppingInsideIsNoticedOnceAndOnlyOnce() {
        WayportWalkIn.Still still = new WayportWalkIn.Still();
        int yes = 0, firstYes = -1;
        for (int i = 0; i < 60; i++) {
            if (still.look(3.5, 64, 7.5)) {
                yes++;
                if (firstYes < 0) firstYes = i;
            }
        }
        assertEquals(1, yes, "a canceled trip must not restart while they stand there");
        assertEquals(WayportWalkIn.STILL_LOOKS - 1, firstYes);
    }

    @Test
    void aStepStartsTheWaitAgainAndAHeadTurnDoesNot() {
        WayportWalkIn.Still still = new WayportWalkIn.Still();
        assertFalse(still.look(0, 64, 0));
        assertFalse(still.look(0, 64, 0));
        assertFalse(still.look(0.3, 64, 0), "a step");
        assertFalse(still.look(0.3, 64, 0));
        assertFalse(still.look(0.305, 64, 0), "drift well under a step");
        assertTrue(still.look(0.305, 64, 0));
    }
}
