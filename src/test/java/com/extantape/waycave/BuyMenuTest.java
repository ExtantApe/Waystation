package com.extantape.waycave;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.waycave.BuyMenu.Action;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The buying menu's rules and words, without a server. Who is offered what, and the clicks, are in the bot test. */
class BuyMenuTest {
    private static ConfigurationSection messages() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(BuyMenuTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8))
            .getConfigurationSection("messages");
    }

    private static String say(String key, String... pairs) {
        TagResolver.Builder tags = TagResolver.builder().resolver(Placeholder.parsed("choose", "[Choose Legacy]"));
        for (int i = 0; i + 1 < pairs.length; i += 2) tags.resolver(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(messages().getString(key), tags.build()));
    }

    @Test void anOfferNeedsAllThree() {
        assertTrue(BuyMenu.eligible(true, true, true));
        assertFalse(BuyMenu.eligible(false, true, true), "not for sale");
        assertFalse(BuyMenu.eligible(true, false, true), "a gate this player's group can't see");
        assertFalse(BuyMenu.eligible(true, true, false), "money with no Vault or economy plugin");
    }

    @Test void rowsGrowWithTheListAndStopAtSix() {
        assertEquals(1, BuyMenu.rows(0));
        assertEquals(1, BuyMenu.rows(9));
        assertEquals(2, BuyMenu.rows(10));
        assertEquals(6, BuyMenu.rows(54));
        assertEquals(6, BuyMenu.rows(200));
    }

    @Test void onlyAFilledSlotChoosesAnOffer() {
        assertEquals(Action.CHOOSE, BuyMenu.listClick(0, 3).action());
        assertEquals(2, BuyMenu.listClick(2, 3).index());
        assertEquals(Action.NONE, BuyMenu.listClick(3, 3).action(), "an empty slot");
        assertEquals(Action.NONE, BuyMenu.listClick(-1, 3).action(), "outside the menu");
        assertEquals(Action.NONE, BuyMenu.listClick(54, 99).action(), "past the last row");
    }

    @Test void buyingNeedsTheSecondClickOnYesAndNothingElseBuys() {
        assertEquals(Action.YES, BuyMenu.confirmClick(BuyMenu.YES).action());
        assertEquals(Action.NO, BuyMenu.confirmClick(BuyMenu.NO).action());
        for (int slot = -1; slot < 40; slot++) {
            if (slot == BuyMenu.YES || slot == BuyMenu.NO) continue;
            assertEquals(Action.NONE, BuyMenu.confirmClick(slot).action(), "slot " + slot);
        }
        assertEquals(Action.NONE, BuyMenu.confirmClick(BuyMenu.SHOWN).action(), "the picture of the gate isn't a button");
    }

    @Test void theYesAndNoSquaresFitTheirScreen() {
        assertTrue(BuyMenu.NO < BuyMenu.CONFIRM_SIZE && BuyMenu.YES < BuyMenu.CONFIRM_SIZE && BuyMenu.SHOWN < BuyMenu.CONFIRM_SIZE);
    }

    @Test void theChatLinesNameTheGatePriceAndBalanceInPlainWords() {
        String line = say("buy-menu-line", "world", "Legacy", "cost", "$40", "have", "$100");
        assertTrue(line.contains("Legacy") && line.contains("costs $40") && line.contains("You have $100"), line);
        assertTrue(line.contains("[Choose Legacy]"), "a name to click comes with it");
        assertTrue(say("buy-menu-confirm", "world", "Legacy", "cost", "$40", "have", "$100").contains("Click Yes"));
        assertTrue(say("buy-menu-cant", "world", "Legacy", "cost", "$40", "have", "$5").contains("Nothing was charged"));
    }

    @Test void yesAndNoAreWordsNotJustColours() {
        assertTrue(say("buy-menu-yes", "world", "Legacy", "cost", "$40").startsWith("Yes"));
        assertTrue(say("buy-menu-no").startsWith("No"));
        assertTrue(say("buy-menu-short").contains("Not enough"), "the grey square says why, in words");
    }

    @Test void noTextPlayersReadTeachesACommand() {
        for (String key : messages().getKeys(false)) {
            if (!key.startsWith("buy-menu") && !key.equals("gate-locked")) continue;
            assertFalse(messages().getString(key).replaceAll("<[^>]*>", "").contains("/"), key + " must not teach a command (D284)");
        }
        assertFalse(Labels.price("$40").contains("/"));
    }

    @Test void everyMenuMessageIsShipped() {
        for (String key : List.of("buy-menu-title", "buy-menu-confirm-title", "buy-menu-none", "buy-menu-no-permission", "buy-menu-intro",
            "buy-menu-line", "buy-menu-choose-link", "buy-menu-confirm", "buy-menu-cant", "buy-menu-gone", "buy-menu-yes", "buy-menu-no",
            "buy-menu-short", "buy-menu-item-cost", "buy-menu-item-have", "buy-menu-choose", "buy-menu-item-ok", "buy-menu-item-short")) {
            assertNotNull(messages().getString(key), key);
            assertFalse(messages().getString(key).isBlank(), key);
        }
    }

    @Test void theBoardPointsAtTheBoardNotACommand() {
        assertTrue(TrailsBoard.lineFor("Legacy", TrailsBoard.State.FOR_SALE).text().contains("click the board"));
    }
}
