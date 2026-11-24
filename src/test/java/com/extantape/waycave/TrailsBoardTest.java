package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.waycave.TrailsBoard.State;
import java.io.StringReader;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The board's text and rules, without a server. Which worlds a player gets is in the bot test. */
class TrailsBoardTest {
    @Test void headingsAndWorldsInOrder() {
        String text = TrailsBoard.render("<gold>Trails", List.of(
            new TrailsBoard.Line(true, "Survival Line"),
            new TrailsBoard.Line(false, "<green>Meadow"),
            new TrailsBoard.Line(true, "Creative Line"),
            new TrailsBoard.Line(false, "<aqua>Flatlands <gray>(buy at the gate)")), "none");
        assertTrue(text.startsWith("<gold>Trails"));
        assertTrue(text.indexOf("Survival Line") < text.indexOf("Meadow") && text.indexOf("Meadow") < text.indexOf("Creative Line"));
        assertTrue(text.contains("(buy at the gate)"), "a locked world says how to open it, in words");
    }

    @Test void anEmptyBoardSaysSoInWords() {
        assertEquals("T\n<gray>No trails are open to you yet.", TrailsBoard.render("T", List.of(), "No trails are open to you yet."));
    }

    @Test void chamberNamesCantInjectFormatting() {
        String text = TrailsBoard.render("T", List.of(new TrailsBoard.Line(true, "<red>Evil")), "x");
        assertTrue(text.contains("\\<red>Evil"), text);
    }

    @Test void aClosedTrailReadsTrailClosedInWordsAndAnOpenOneDoesNot() {
        assertEquals("<green>Meadow <gray>(trail closed)", TrailsBoard.lineFor("<green>Meadow", State.CLOSED).text());
        assertEquals("<green>Meadow", TrailsBoard.lineFor("<green>Meadow", State.OPEN).text());
        assertNull(TrailsBoard.lineFor("<green>Meadow", State.HIDDEN), "a world that isn't theirs isn't named at all");
    }

    @Test void aWorldTheyNeverHadIsNeverNamed() {
        // can enter, for sale, had before, hidden group
        assertEquals(State.HIDDEN, TrailsBoard.stateOf(false, false, false, false), "no access now and none before");
        assertEquals(State.CLOSED, TrailsBoard.stateOf(false, false, true, false), "had it, lost it");
        assertEquals(State.OPEN, TrailsBoard.stateOf(true, false, true, false));
        assertEquals(State.OPEN, TrailsBoard.stateOf(true, false, false, false));
        assertEquals(State.FOR_SALE, TrailsBoard.stateOf(false, true, false, false));
        assertEquals(State.HIDDEN, TrailsBoard.stateOf(false, false, true, true), "a group that always stays hidden never reads closed");
    }

    @Test void settingsHaveSensibleDefaultsAndLimits() {
        var d = Settings.readBoard(new YamlConfiguration());
        assertTrue(d.enabled());
        assertEquals(5, d.distance());
        assertEquals("<gold><bold>Trails</bold>", d.title());
        assertThrows(IllegalArgumentException.class, () -> Settings.readBoard(YamlConfiguration.loadConfiguration(new StringReader("trails-board:\n  distance: 1\n"))));
        assertThrows(IllegalArgumentException.class, () -> Settings.readBoard(YamlConfiguration.loadConfiguration(new StringReader("trails-board:\n  distance: 31\n"))));
    }
}
