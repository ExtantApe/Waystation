package com.extantape.waystation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;

/**
 * The pass a fargate gives a player for one server, carried as a transfer cookie: who it's for, which server, when it
 * was made and when it runs out, and a random nonce, signed with HMAC-SHA256 under a key both servers share. The
 * receiving server's arrival guard admits a transferred player only with a valid, unused pass for them and for it.
 *
 * <p>Owner, 2026-11-10 (D42): nobody reaches the guarded server except through the sending server's fargate.
 */
final class TransferPass {
    static final int VERSION = 1;
    static final int MIN_KEY_BYTES = 32;
    static final int NONCE_BYTES = 16;
    static final int MAC_BYTES = 32;
    /** Clocks may differ this much between the two servers. */
    static final long SKEW_MS = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Check { OK, MALFORMED, BAD_SIGNATURE, WRONG_PLAYER, WRONG_SERVER, EXPIRED, NOT_YET_VALID }

    record Read(Check check, byte[] nonce) {}

    private TransferPass() {}

    /** A new pass for this player and server, valid for {@code ttlMs} from {@code nowMs}. */
    static byte[] make(byte[] key, UUID player, String server, long nowMs, long ttlMs) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return make(key, player, server, nowMs, ttlMs, nonce);
    }

    static byte[] make(byte[] key, UUID player, String server, long nowMs, long ttlMs, byte[] nonce) {
        byte[] name = server.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(1 + 16 + 2 + name.length + 8 + 8 + NONCE_BYTES + MAC_BYTES);
        b.put((byte) VERSION).putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits())
            .putShort((short) name.length).put(name).putLong(nowMs).putLong(nowMs + ttlMs).put(nonce);
        byte[] signed = new byte[b.position()];
        b.flip();
        b.get(signed);
        byte[] mac = mac(key, signed);
        byte[] out = new byte[signed.length + MAC_BYTES];
        System.arraycopy(signed, 0, out, 0, signed.length);
        System.arraycopy(mac, 0, out, signed.length, MAC_BYTES);
        return out;
    }

    /** Checks a pass for this player arriving at this server. The signature is checked before anything inside is trusted. */
    static Read check(byte[] key, byte[] pass, UUID player, String server, long nowMs) {
        if (pass == null || pass.length < 1 + 16 + 2 + 8 + 8 + NONCE_BYTES + MAC_BYTES || pass.length > 512) return new Read(Check.MALFORMED, null);
        int body = pass.length - MAC_BYTES;
        byte[] signed = new byte[body], mac = new byte[MAC_BYTES];
        System.arraycopy(pass, 0, signed, 0, body);
        System.arraycopy(pass, body, mac, 0, MAC_BYTES);
        if (!MessageDigest.isEqual(mac(key, signed), mac)) return new Read(Check.BAD_SIGNATURE, null);
        ByteBuffer b = ByteBuffer.wrap(signed);
        if (b.get() != VERSION) return new Read(Check.MALFORMED, null);
        UUID who = new UUID(b.getLong(), b.getLong());
        int n = b.getShort() & 0xffff;
        if (n > b.remaining() - 8 - 8 - NONCE_BYTES || n != b.remaining() - 8 - 8 - NONCE_BYTES) return new Read(Check.MALFORMED, null);
        byte[] name = new byte[n];
        b.get(name);
        long made = b.getLong(), until = b.getLong();
        byte[] nonce = new byte[NONCE_BYTES];
        b.get(nonce);
        if (!who.equals(player)) return new Read(Check.WRONG_PLAYER, nonce);
        if (!new String(name, StandardCharsets.UTF_8).equals(server)) return new Read(Check.WRONG_SERVER, nonce);
        if (made > nowMs + SKEW_MS) return new Read(Check.NOT_YET_VALID, nonce);
        if (nowMs > until + SKEW_MS) return new Read(Check.EXPIRED, nonce);
        return new Read(Check.OK, nonce);
    }

    private static byte[] mac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shared key file, or a problem saying exactly what's wrong with it. Never the key's contents. */
    record Key(byte[] bytes, String problem) {
        boolean usable() { return problem == null; }
    }

    static Key readKey(Path file) {
        try {
            if (!Files.isRegularFile(file)) return new Key(null, file.getFileName() + " is missing");
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                for (PosixFilePermission p : perms) {
                    if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE) {
                        return new Key(null, file.getFileName() + " can be read or run by others; it must be readable by the server's user only (chmod 600)");
                    }
                }
            } catch (UnsupportedOperationException notPosix) {
                // Not a POSIX file system: nothing to check here.
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < MIN_KEY_BYTES) return new Key(null, file.getFileName() + " holds " + bytes.length + " bytes; it needs at least " + MIN_KEY_BYTES + " random bytes");
            return new Key(bytes, null);
        } catch (IOException e) {
            return new Key(null, file.getFileName() + " couldn't be read (" + e.getClass().getSimpleName() + ")");
        }
    }
}
