package com.extantape.waystation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The wall map's picture, without a server. What each player is sent is in the bot test. */
class StationPictureTest {
    static final List<StationPicture.Mark> TWO = List.of(
        new StationPicture.Mark(20, -10, true, 3, 1), new StationPicture.Mark(26, -10, true, 3, 1),
        new StationPicture.Mark(-30, 15, false, 3, 2));

    @Test void everyGateFitsAndTheCentreIsInTheMiddle() {
        var p = StationPicture.draw(256, 0, 0, TWO, 5);
        for (var m : TWO) {
            int x = p.toPixelX(m.x() + 0.5), y = p.toPixelZ(m.z() + 0.5);
            assertTrue(x >= 0 && y >= 0 && x < 256 && y < 256, m.toString());
        }
        assertEquals(128, p.toPixelX(0), 1);
        assertEquals(StationPicture.CENTRE, p.at(p.toPixelX(0.5), p.toPixelZ(0.5)));
    }

    @Test void gatesAreMarkedOnTheirPlatformsColour() {
        var p = StationPicture.draw(256, 0, 0, TWO, 5);
        assertEquals(StationPicture.GATE, p.at(p.toPixelX(26.5), p.toPixelZ(-9.5)), "the gate itself");
        int beside = p.at(p.toPixelX(26.5), p.toPixelZ(-6.5));
        assertEquals(StationPicture.PLATFORM[0], beside, "platform 1's floor, a little away from the gate");
        assertEquals(StationPicture.PLATFORM[1], p.at(p.toPixelX(-26.5), p.toPixelZ(15.5)), "platform 2's floor");
    }

    @Test void eachPlatformCarriesItsNumberNotJustAColour() {
        var p = StationPicture.draw(256, 0, 0, TWO, 5);
        int white = 0;
        for (int v : p.rgb) if (v == StationPicture.TEXT) white++;
        // Gates are white too; numbers add more white pixels than the gates alone.
        var gatesOnly = StationPicture.draw(256, 0, 0, List.of(), 5);
        assertTrue(white > 40, "numbers are drawn: " + white);
        assertEquals(0, java.util.Arrays.stream(gatesOnly.rgb).filter(v -> v == StationPicture.TEXT).count(), "nothing white with no gates");
    }

    @Test void gatesThePlayerCantSeeLeaveNoTrace() {
        var all = StationPicture.draw(256, 0, 0, TWO, 5);
        var some = StationPicture.draw(256, 0, 0, TWO.subList(0, 2), 5);
        assertNotEquals(StationPicture.PLATFORM[1], some.at(some.toPixelX(-29.5), some.toPixelZ(15.5)), "platform 2 isn't drawn");
        assertEquals(StationPicture.PLATFORM[1], all.at(all.toPixelX(-26.5), all.toPixelZ(15.5)));
    }

    @Test void platformColoursStayDistinct() {
        assertEquals(8, java.util.Arrays.stream(StationPicture.PLATFORM).distinct().count());
    }
}
