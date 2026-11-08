package com.extantape.waystation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Shipped labels stay readable and never rely on colour alone.
 *
 * Contrast is WCAG's ratio, taken against the worst place a label can float: in front of something white, seen
 * through its dark plate, and in front of something black. Floating labels are drawn large (scale 0.8 and up, read
 * from a few blocks away), so they're held to WCAG's 3:1 for large text. This is arithmetic on the colours; how it
 * looks in a real client is still to be seen.
 */
class ReadabilityTest {
    static final double LARGE_TEXT = 3.0;

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                ReadabilityTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    /** Every floating label Waystation ships, with sample values in place of names and prices. */
    private static Map<String, String> labels() {
        YamlConfiguration c = shipped();
        Map<String, String> all = new LinkedHashMap<>();
        all.put("return-label", c.getString("station.return-label"));
        all.put("station-label", c.getString("station.station-label"));
        all.put("dead-label", c.getString("station.dead-label").replace("<name>", "Survival"));
        all.put("fargate-label", c.getString("fargate-label").replace("<name>", "Example Server"));
        all.put("island-posts.label", c.getString("island-posts.label"));
        all.put("price", Labels.price("3 XP levels and $40", "legacy"));
        all.put("platform sign", Labels.platform(1, "Survival"));
        all.put("tribute", Labels.TRIBUTE);
        all.put("example world label", "<aqua>OneBlock");
        return all;
    }

    @Test
    void everyShippedLabelReadsOverWhiteAndOverBlack() {
        for (var e : labels().entrySet()) {
            for (TextColor color : colours(e.getValue())) {
                double overWhite = contrast(color, plate(Labels.DEFAULT_BACKING, 255));
                double overBlack = contrast(color, plate(Labels.DEFAULT_BACKING, 0));
                assertTrue(overWhite >= LARGE_TEXT, e.getKey() + " " + color + " over white: " + overWhite);
                assertTrue(overBlack >= LARGE_TEXT, e.getKey() + " " + color + " over black: " + overBlack);
            }
        }
    }

    @Test
    void theCheckCanFail() {
        // Positive controls: dark grey fails even on the plate, and gold fails on Minecraft's own faint background.
        assertFalse(contrast(NamedTextColor.DARK_GRAY, plate(Labels.DEFAULT_BACKING, 0)) >= LARGE_TEXT);
        assertFalse(contrast(NamedTextColor.GOLD, plate(25, 255)) >= LARGE_TEXT);
    }

    @Test
    void statesAreSaidInWordsNotOnlyInColour() {
        Map<String, String> l = labels();
        assertTrue(plain(l.get("dead-label")).contains("line closed"), l.get("dead-label"));
        assertTrue(plain(l.get("fargate-label")).startsWith("Leaving this server"), l.get("fargate-label"));
        assertTrue(plain(l.get("price")).startsWith("Locked:"), l.get("price"));
        assertTrue(plain(l.get("price")).contains("/buygate legacy"), l.get("price"));
    }

    @Test
    void theCountdownIsWhiteBecauseTheActionBarHasNoPlate() {
        YamlConfiguration c = shipped();
        for (String key : List.of("return-countdown", "return-checking")) {
            String text = c.getString("messages." + key).replace("<destination>", "Home").replace("<seconds>", "5");
            for (TextColor color : colours(text)) assertEquals(NamedTextColor.WHITE, color, key);
        }
    }

    @Test
    void theShippedConfigUsesTheDefaultPlate() {
        assertEquals(Labels.DEFAULT_BACKING, shipped().getInt("label-backing"));
        assertEquals(191, Labels.alpha(75));
        assertEquals(255, Labels.alpha(150));
    }

    // ---- colour arithmetic ----

    /** The grey you see through a black plate of this strength, in front of something of this brightness (0-255). */
    static int plate(int backingPercent, int behind) {
        double a = Labels.alpha(backingPercent) / 255.0;
        return (int) Math.round((1 - a) * behind);
    }

    static double contrast(TextColor text, int grey) {
        double l1 = luminance(text.red(), text.green(), text.blue());
        double l2 = luminance(grey, grey, grey);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    static double luminance(int r, int g, int b) {
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** The colour of every visible piece of text, white where none is set (Minecraft's default for labels). */
    static List<TextColor> colours(String miniMessage) {
        List<TextColor> out = new ArrayList<>();
        walk(MiniMessage.miniMessage().deserialize(miniMessage), NamedTextColor.WHITE, out);
        return out;
    }

    private static void walk(Component c, TextColor inherited, List<TextColor> out) {
        TextColor color = c.color() != null ? c.color() : inherited;
        if (c instanceof TextComponent t && !t.content().isBlank()) out.add(color);
        for (Component child : c.children()) walk(child, color, out);
    }

    private static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }
}
