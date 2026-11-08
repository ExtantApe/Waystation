package com.extantape.waystation;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Asks a Minecraft server for its status, the way the server list does. A server counts as up only
 * when it answers with a status that names a version, so an open port with something else behind it
 * counts as down.
 */
final class StatusPing {
    private StatusPing() {}

    /** True if a Minecraft server answered at host:port within timeoutMs. Never throws. */
    static boolean isUp(String host, int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00); // handshake packet
            writeVarInt(handshake, -1); // protocol version: any, as the server list does when it doesn't know
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1); // next state: status
            // Both packets in one write, so they go out together.
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            writePacket(request, handshake.toByteArray());
            writePacket(request, new byte[] {0x00}); // status request
            out.write(request.toByteArray());
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = readVarInt(in);
            if (length <= 0 || length > 1 << 21) return false;
            if (System.currentTimeMillis() > deadline) return false;
            byte[] body = in.readNBytes(length);
            if (body.length != length) return false;
            DataInputStream packet = new DataInputStream(new java.io.ByteArrayInputStream(body));
            if (readVarInt(packet) != 0x00) return false;
            int jsonLength = readVarInt(packet);
            if (jsonLength <= 0 || jsonLength > body.length) return false;
            String json = new String(packet.readNBytes(jsonLength), StandardCharsets.UTF_8);
            return json.startsWith("{") && json.contains("\"version\"");
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    private static void writePacket(ByteArrayOutputStream out, byte[] body) {
        writeVarInt(out, body.length);
        out.writeBytes(body);
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) throw new IOException("stream ended inside a VarInt");
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }
}
