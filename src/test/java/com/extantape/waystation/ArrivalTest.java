package com.extantape.waystation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The arrival flow's pieces that don't need a running server. */
class ArrivalTest {
    static YamlConfiguration yaml(String s) { return YamlConfiguration.loadConfiguration(new StringReader(s)); }

    @Test void theShippedConfigHasALobbyGateButNoWorldUntilOneIsSet() {
        var c = YamlConfiguration.loadConfiguration(new InputStreamReader(ArrivalTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        var a = Settings.readArrival(c);
        assertTrue(a.lobbyGate());
        assertEquals("", a.lobbyWorld());
        assertNull(a.lobbySpot());
        assertTrue(a.welcome());
        assertEquals(30, a.welcomeAfterDays());
        assertEquals(3, a.welcomeText().size());
        assertTrue(a.welcomeText().get(1).contains("<worlds>") && a.welcomeText().get(1).contains("<groups>"));
    }

    @Test void aLobbySpotNeedsAllThreeCoordinates() {
        assertArrayEquals(new double[] {1, 65, 2}, Settings.readArrival(yaml("arrival:\n  lobby-world: hub\n  lobby-spot: {x: 1, y: 65, z: 2}\n")).lobbySpot());
        assertThrows(IllegalArgumentException.class, () -> Settings.readArrival(yaml("arrival:\n  lobby-spot: {x: 1, z: 2}\n")));
    }

    @Test void theWelcomeStaysShortAndTheGapSensible() {
        assertThrows(IllegalArgumentException.class, () -> Settings.readArrival(yaml("arrival:\n  welcome-after-days: 0\n")));
        assertThrows(IllegalArgumentException.class, () -> Settings.readArrival(yaml("arrival:\n  welcome-text: [a, b, c, d, e, f, g, h, i]\n")));
    }

    @Test void aWelcomeIsDueOnTheFirstVisitAndAfterTheGap() {
        long day = 86_400_000L, now = 100 * day;
        assertTrue(Arrival.due(null, now, 30), "first visit");
        assertFalse(Arrival.due(now - 29 * day, now, 30));
        assertTrue(Arrival.due(now - 30 * day, now, 30));
        assertFalse(Arrival.due(now, now, 1), "back the same day");
    }

    @Test void countsReadAsWords() {
        assertEquals("no worlds yet", Arrival.count(0, "world"));
        assertEquals("1 world", Arrival.count(1, "world"));
        assertEquals("3 platforms", Arrival.count(3, "platform"));
    }

    @Test void whenTheLobbyGateIsPlacedAgain() {
        String label = "<gold><bold>Central Station</bold>";
        var g = new Gate("return-lobby", "lobby", 10, 64, 10, BlockFace.NORTH, "waystation");
        g.label = label;
        var at = new org.bukkit.Location(null, 10, 64, 10);
        assertFalse(Arrival.needsPlacing(g, label, at, false), "where it should be");
        g.label = "<gold>Central Station";
        assertTrue(Arrival.needsPlacing(g, label, at, false), "placed as an ordinary return gate before the lobby was known");
        g.label = label;
        var moved = new org.bukkit.Location(null, 10 + 30, 64, 10);
        assertTrue(Arrival.needsPlacing(g, label, moved, true), "the lobby moved away");
        assertFalse(Arrival.needsPlacing(g, label, moved, false), "a gate the terrain pushed out is left alone on later checks");
        assertTrue(Arrival.needsPlacing(g, label, new org.bukkit.Location(null, 10 + 73, 64, 10), false), "out of reach");
    }
}
