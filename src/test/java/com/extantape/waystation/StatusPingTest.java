package com.extantape.waystation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatusPingTest {

    /** A one-connection fake server that reads whatever arrives, then answers with reply (or nothing). */
    private static int serve(ServerSocket server, byte[] reply, long delayMs, AtomicReference<byte[]> received) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                s.setSoTimeout(300);
                // Read whatever arrives until the client goes quiet, however it was split up.
                ByteArrayOutputStream got = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                try {
                    for (int n; (n = s.getInputStream().read(buf)) > 0; ) got.write(buf, 0, n);
                } catch (java.net.SocketTimeoutException quiet) {
                    // done reading
                }
                if (received != null) received.set(got.toByteArray());
                if (delayMs > 0) Thread.sleep(delayMs);
                if (reply != null) {
                    OutputStream out = s.getOutputStream();
                    out.write(reply);
                    out.flush();
                }
                Thread.sleep(200);
            } catch (IOException | InterruptedException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    private static byte[] statusReply(String json) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        StatusPing.writeVarInt(body, 0x00);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        StatusPing.writeVarInt(body, bytes.length);
        body.writeBytes(bytes);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        StatusPing.writeVarInt(packet, body.size());
        packet.writeBytes(body.toByteArray());
        return packet.toByteArray();
    }

    private static ServerSocket local() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    @Test
    void upWhenAMinecraftStatusComesBack() throws Exception {
        try (ServerSocket server = local()) {
            AtomicReference<byte[]> got = new AtomicReference<>();
            int port = serve(server, statusReply("{\"version\":{\"name\":\"test\",\"protocol\":1},\"players\":{\"max\":1,\"online\":0}}"), 0, got);
            assertTrue(StatusPing.isUp("127.0.0.1", port, 2000));
            // The handshake asks for status (next state 1), not login.
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(got.get()));
            int length = StatusPing.readVarInt(in);
            byte[] handshake = in.readNBytes(length);
            assertEquals(0x00, handshake[0]);
            assertEquals(1, handshake[handshake.length - 1]);
        }
    }

    @Test
    void downWhenNothingListens() throws Exception {
        int port;
        try (ServerSocket server = local()) {
            port = server.getLocalPort();
        }
        assertFalse(StatusPing.isUp("127.0.0.1", port, 1000));
    }

    @Test
    void downWhenSomethingElseAnswers() throws Exception {
        try (ServerSocket server = local()) {
            int port = serve(server, "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0, null);
            assertFalse(StatusPing.isUp("127.0.0.1", port, 1000));
        }
    }

    @Test
    void downWhenTheConnectionClosesWithoutAnswer() throws Exception {
        try (ServerSocket server = local()) {
            int port = serve(server, null, 0, null);
            assertFalse(StatusPing.isUp("127.0.0.1", port, 1000));
        }
    }

    @Test
    void downWhenTheAnswerIsTooSlow() throws Exception {
        try (ServerSocket server = local()) {
            int port = serve(server, statusReply("{\"version\":{}}"), 1500, null);
            long start = System.currentTimeMillis();
            assertFalse(StatusPing.isUp("127.0.0.1", port, 500));
            assertTrue(System.currentTimeMillis() - start < 1400, "gave up on time");
        }
    }

    @Test
    void downWhenTheHostDoesNotResolve() {
        assertFalse(StatusPing.isUp("no-such-host.invalid", 25565, 1000));
    }

    @Test
    void varIntsRoundTrip() throws Exception {
        for (int v : new int[] {0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StatusPing.writeVarInt(out, v);
            assertEquals(v, StatusPing.readVarInt(new ByteArrayInputStream(out.toByteArray())));
        }
    }
}
