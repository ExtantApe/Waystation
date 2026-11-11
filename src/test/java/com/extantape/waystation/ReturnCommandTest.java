package com.extantape.waystation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The return command's pieces that don't need a running server. */
class ReturnCommandTest {

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                ReturnCommandTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void theShippedConfigHasTheReturnCommandOff() {
        ConfigurationSection r = shipped().getConfigurationSection("return");
        assertFalse(r.getBoolean("enabled"));
        assertEquals("", r.getString("destination"));
        assertEquals("return", r.getString("command"));
        assertEquals("waystation.return", r.getString("permission"));
        assertEquals(5, r.getInt("countdown-seconds"));
    }

    @Test
    void everyReturnMessageTheCodeUsesIsInTheShippedConfig() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/extantape/waystation/ReturnCountdown.java"));
        Set<String> used = new TreeSet<>();
        Matcher m = Pattern.compile("\"(return-[a-z-]+)\"").matcher(source);
        while (m.find()) used.add(m.group(1));
        assertTrue(used.size() >= 13, "found " + used);
        ConfigurationSection messages = shipped().getConfigurationSection("messages");
        for (String key : used) assertFalse(messages.getString(key, "").isBlank(), key);
    }

    @Test
    void theProblemSaysWhatToFix() {
        var target = new Settings.FargateTarget("home", "Home", "127.0.0.1", 25565, Set.of(775), "waystation.fargate.home", false, "", 0);
        Map<String, Settings.FargateTarget> fargates = Map.of("home", target);
        assertEquals("return.enabled is false", ret(false, "home").problem(fargates));
        assertEquals("return.destination is empty", ret(true, "").problem(fargates));
        assertEquals("return.destination \"elsewhere\" isn't under fargate-destinations", ret(true, "elsewhere").problem(fargates));
        assertNull(ret(true, "home").problem(fargates));
    }

    @Test
    void headTurnsAndTinyDriftAreNotMoving() {
        World w = world();
        Location start = new Location(w, 10.5, 64, 10.5, 0, 0);
        assertFalse(ReturnCountdown.moved(start, new Location(w, 10.5, 64, 10.5, 170, -40)));
        assertFalse(ReturnCountdown.moved(start, new Location(w, 10.55, 64, 10.5)));
        assertTrue(ReturnCountdown.moved(start, new Location(w, 10.75, 64, 10.5)));
        assertTrue(ReturnCountdown.moved(start, new Location(w, 10.5, 64.5, 10.5)));
        assertTrue(ReturnCountdown.moved(start, new Location(world(), 10.5, 64, 10.5)));
    }

    private static Settings.Return ret(boolean enabled, String destination) {
        return new Settings.Return(enabled, destination, "return", List.of(), "waystation.return", 5, true, "", 0, 3000);
    }

    /** A stand-in world: equal only to itself, which is all the movement check asks of it. */
    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (p, m, a) ->
                switch (m.getName()) {
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    case "toString" -> "world";
                    default -> null;
                });
    }
}
