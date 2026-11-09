package com.extantape.waystation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The board's text and settings, without a server. Which worlds a player gets is in the bot test. */
class DeparturesBoardTest {
    @Test void headingsAndWorldsInOrder() {
        String text = DeparturesBoard.render("<gold>Departures", List.of(
            new DeparturesBoard.Line(true, "Platform 1 · Survival Line"),
            new DeparturesBoard.Line(false, "<green>Meadow"),
            new DeparturesBoard.Line(true, "Platform 2 · Creative Line"),
            new DeparturesBoard.Line(false, "<aqua>Flatlands <gray>(buy at the gate)")), "none");
        assertTrue(text.startsWith("<gold>Departures"));
        assertTrue(text.indexOf("Survival Line") < text.indexOf("Meadow") && text.indexOf("Meadow") < text.indexOf("Creative Line"));
        assertTrue(text.contains("(buy at the gate)"), "a locked world says how to open it, in words");
    }

    @Test void anEmptyBoardSaysSoInWords() {
        assertEquals("T\n<gray>No gates are open to you yet.", DeparturesBoard.render("T", List.of(), "No gates are open to you yet."));
    }

    @Test void platformNamesCantInjectFormatting() {
        String text = DeparturesBoard.render("T", List.of(new DeparturesBoard.Line(true, "Platform 1 · <red>Evil")), "x");
        assertTrue(text.contains("\\<red>Evil"), text);
    }

    @Test void settingsHaveSensibleDefaultsAndLimits() {
        var d = Settings.readBoard(new YamlConfiguration());
        assertTrue(d.enabled());
        assertEquals(5, d.distance());
        assertThrows(IllegalArgumentException.class, () -> Settings.readBoard(YamlConfiguration.loadConfiguration(new StringReader("departures-board:\n  distance: 1\n"))));
        assertThrows(IllegalArgumentException.class, () -> Settings.readBoard(YamlConfiguration.loadConfiguration(new StringReader("departures-board:\n  distance: 31\n"))));
    }
}
