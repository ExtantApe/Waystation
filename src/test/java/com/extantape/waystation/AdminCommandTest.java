package com.extantape.waystation;

import com.extantape.signalbox.Describe;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** /gate's --json side that needs no server: describe, and exactly one answer per request. */
class AdminCommandTest {
    final List<String> said = new ArrayList<>();
    final CommandSender console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {CommandSender.class},
        (proxy, m, args) -> {
            if (m.getName().equals("sendMessage") && args != null && args[0] instanceof String s) said.add(s);
            return null;
        });

    @Test void describeListsEverySubcommandButHelp() {
        List<String> named = AdminCommand.described().stream().map(Describe.Command::name).toList();
        List<String> expected = AdminCommand.SUBCOMMANDS.stream().filter(s -> !s.equals("help")).toList();
        assertEquals(expected.stream().sorted().toList(), named.stream().sorted().toList());
    }

    @Test void everyCommandNeedsItsOwnNode() {
        for (Describe.Command c : AdminCommand.described()) assertEquals("waystation.admin." + c.name(), c.permission(), c.name());
    }

    @Test void optionalArgumentsOnlyComeLast() {
        for (Describe.Command c : AdminCommand.described()) {
            boolean optional = false;
            for (Describe.Arg a : c.args()) {
                assertFalse(optional && !a.optional(), c.name() + ": " + a.name() + " is required after an optional one");
                optional |= a.optional();
            }
        }
    }

    @Test void aJsonRequestIsAnsweredExactlyOnceEvenIfTheCodeTriesTwice() {
        var out = new AdminCommand.JsonOut(console, "w1");
        out.put("gate", "g1");
        out.warn("It stays hidden until accepts-transfers=true is set in server.properties.");
        out.ok();
        out.fail("internal", "late");
        out.ok();
        assertEquals(1, said.size(), String.valueOf(said));
        assertTrue(said.getFirst().startsWith("ea-result w1 {") && said.getFirst().contains("\"ok\":true")
            && said.getFirst().contains("\"warnings\":[\"It stays hidden"), said.getFirst());
    }

    @Test void aFailureIsTheOnlyAnswer() {
        var out = new AdminCommand.JsonOut(console, "w2");
        out.fail("not-found", "No gate called nope");
        out.ok();
        assertEquals(1, said.size());
        assertTrue(said.getFirst().contains("\"ok\":false") && said.getFirst().contains("\"code\":\"not-found\""), said.getFirst());
    }

    @Test void peopleGetNoJson() {
        var out = new AdminCommand.PlainOut(console);
        out.put("gate", "g1");
        out.ok();
        assertTrue(said.isEmpty(), "plain output sends Components, never an answer line");
    }
}
