package com.extantape.waystation;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** D42: only a fargate pass gets anyone onto a guarded server. Everything that can't be checked is a refusal. */
class ArrivalGuardTest {
    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    static final UUID KID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final ArrivalGuard guard = new ArrivalGuard(quiet(), now::get);
    final ArrivalGuard.Config on = new ArrivalGuard.Config(true, "second", "Through the gate, please.", new TransferPass.Key(KEY, null));

    static Logger quiet() { Logger l = Logger.getAnonymousLogger(); l.setUseParentHandlers(false); return l; }

    /** A client connection: whether it was transferred, and what it answers when asked for the pass. */
    static PlayerConfigurationConnection conn(boolean transferred, CompletableFuture<byte[]> cookie) {
        return (PlayerConfigurationConnection) Proxy.newProxyInstance(PlayerConfigurationConnection.class.getClassLoader(),
            new Class<?>[] {PlayerConfigurationConnection.class}, (p, m, a) -> switch (m.getName()) {
                case "isTransferred" -> transferred;
                case "retrieveCookie" -> cookie;
                default -> null;
            });
    }

    byte[] pass(UUID who, String server) { return TransferPass.make(KEY, who, server, now.get(), 60_000); }

    @Test void controlAValidPassFromTheFargateGetsIn() {
        assertNull(guard.refusal(on, conn(true, CompletableFuture.completedFuture(pass(KID, "second"))), KID));
    }

    @Test void aDirectJoinIsRefused() {
        assertEquals("a direct join, not a transfer", guard.refusal(on, conn(false, CompletableFuture.completedFuture(pass(KID, "second"))), KID));
    }

    @Test void aTransferWithoutAPassIsRefused() {
        assertNotNull(guard.refusal(on, conn(true, CompletableFuture.completedFuture(null)), KID));
        assertNotNull(guard.refusal(on, conn(true, CompletableFuture.completedFuture(new byte[0])), KID));
        assertNotNull(guard.refusal(on, conn(true, CompletableFuture.failedFuture(new IllegalStateException())), KID), "a client that can't answer");
        assertNotNull(guard.refusal(on, conn(true, new CompletableFuture<>()), KID), "a client that never answers, after the wait");
    }

    @Test void aPassIsForOnePlayerAndOneServer() {
        assertEquals("pass wrong player", guard.judge(on, pass(OTHER, "second"), KID));
        assertEquals("pass wrong server", guard.judge(on, pass(KID, "elsewhere"), KID));
    }

    @Test void aPassWorksOnce() {
        byte[] p = pass(KID, "second");
        assertNull(guard.judge(on, p, KID));
        assertEquals("pass already used", guard.judge(on, p, KID));
    }

    @Test void aPassRunsOut() {
        byte[] p = pass(KID, "second");
        now.addAndGet(60_000 + TransferPass.SKEW_MS + 1);
        assertEquals("pass expired", guard.judge(on, p, KID));
    }

    @Test void aForgedOrAlteredPassIsRefused() {
        byte[] p = pass(KID, "second");
        p[5] ^= 1;
        assertEquals("pass bad signature", guard.judge(on, p, KID));
        byte[] other = TransferPass.make("another key, also 32 bytes long.".getBytes(), KID, "second", now.get(), 60_000);
        assertEquals("pass bad signature", guard.judge(on, other, KID));
        assertEquals("pass malformed", guard.judge(on, new byte[] {1, 2, 3}, KID));
    }

    @Test void withoutAUsableKeyNobodyGetsIn() {
        var noKey = new ArrivalGuard.Config(true, "second", "x", new TransferPass.Key(null, "transfer.key is missing"));
        assertNotNull(guard.refusal(noKey, conn(true, CompletableFuture.completedFuture(pass(KID, "second"))), KID));
    }

    @TempDir Path dir;

    @Test void theKeyFileMustBeLongEnoughAndPrivate() throws Exception {
        Path f = dir.resolve("transfer.key");
        assertFalse(TransferPass.readKey(f).usable(), "missing");
        Files.write(f, new byte[16]);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        assertFalse(TransferPass.readKey(f).usable(), "too short");
        Files.write(f, KEY);
        assertTrue(TransferPass.readKey(f).usable());
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        var open = TransferPass.readKey(f);
        assertFalse(open.usable(), "readable by others");
        assertFalse(open.problem().contains(new String(KEY)), "a problem never shows the key");
    }
}
