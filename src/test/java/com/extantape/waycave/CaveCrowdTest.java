package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The two rules that keep kids safe in the cave, and where people arrive, without a server. */
class CaveCrowdTest {
    @Test
    void twoPlayersSeeEachOtherOnlyWhenPosseSaysYesBothWays() {
        assertTrue(CaveCrowd.mayBeSeen(true, true));
        assertFalse(CaveCrowd.mayBeSeen(true, false));
        assertFalse(CaveCrowd.mayBeSeen(false, true));
        assertFalse(CaveCrowd.mayBeSeen(false, false));
    }

    @Test
    void whenPosseCantAnswerNobodySeesAnybody() {
        assertFalse(CaveCrowd.mayBeSeen(null, null), "no Posse, or one that isn't running");
        assertFalse(CaveCrowd.mayBeSeen(true, null));
        assertFalse(CaveCrowd.mayBeSeen(null, true));
    }

    @Test
    void youArriveAtTheEndOfYourLastTrailOnlyWhenEveryPartOfThatStillHolds() {
        assertEquals(CaveArrival.Spot.LAST_TRAIL, CaveArrival.choose(true, true, true, true));
        assertEquals(CaveArrival.Spot.START, CaveArrival.choose(false, false, false, false), "a first visit");
        assertEquals(CaveArrival.Spot.START, CaveArrival.choose(true, false, true, true), "the gate was removed");
        assertEquals(CaveArrival.Spot.START, CaveArrival.choose(true, true, false, true), "they lost access: never at a gate they can't use");
        assertEquals(CaveArrival.Spot.START, CaveArrival.choose(true, true, true, false), "the spot is unsafe or gone after a rebuild");
    }
}
