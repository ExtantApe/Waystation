package com.extantape.waycave;

import org.bukkit.Color;
import org.bukkit.entity.TextDisplay;

/**
 * Floating labels, and how they're drawn so they stay readable. A label can float over snow, sky or a dark cave, so each
 * gets a text shadow and a dark backing plate. Wherever a label marks a state (locked, closed, leaving), it says so in
 * words, so nobody has to tell it apart by colour. ReadabilityTest holds the shipped labels to both.
 */
final class Labels {
    /** How dark the plate behind a label is, in percent. Minecraft's own is about 25, too faint over snow or sky. */
    static final int DEFAULT_BACKING = 75;
    static final String TRIBUTE = "<light_purple>Tribute";

    private Labels() {}

    static String price(String cost) {
        return "<yellow>Locked: " + cost + "\n<white>Buy it at the Trails board";
    }

    /** A chamber's sign: its name, the group's label. */
    static String platform(int number, String label) {
        return "<yellow>" + label + "</yellow>";
    }

    /** Shadow, and the backing plate. 0 keeps Minecraft's own background. */
    static void style(TextDisplay e, int backingPercent) {
        e.setShadowed(true);
        if (backingPercent <= 0) return;
        e.setBackgroundColor(Color.fromARGB(alpha(backingPercent), 0, 0, 0));
    }

    static int alpha(int backingPercent) {
        return Math.round(Math.min(100, backingPercent) * 255 / 100f);
    }
}
