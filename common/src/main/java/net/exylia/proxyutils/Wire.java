package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The bytes on the {@code exylia:bridge} channel, in both directions.
 *
 * <p>A request from a server is {@code UTF module, int id, UTF payload}; the
 * answer back is {@code UTF module, int id, byte status, UTF detail}; a
 * message the proxy sends unasked is an answer with id 0. ExyliaLib
 * ({@code net.exylia.lib.proxy.internal.Wire}) reads and writes exactly this,
 * so a change here is a change there.
 */
public final class Wire {

    /** The channel both sides register; lowercase and namespaced, as the protocol requires. */
    public static final String CHANNEL = "exylia:bridge";

    private Wire() {
        throw new AssertionError("No instances.");
    }

    /** One request, as a server sent it. */
    public record Frame(@NotNull String module, int id, @NotNull String payload) {
    }

    /**
     * Reads a request.
     *
     * @throws IOException if the bytes are not one; the caller drops them
     */
    public static @NotNull Frame decode(byte @NotNull [] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        return new Frame(in.readUTF(), in.readInt(), in.readUTF());
    }

    /**
     * Writes a message the proxy sends on its own, with no request behind it.
     *
     * <p>Same frame as an answer, with id 0: ExyliaLib hands anything with
     * that id to whichever module handler it registered rather than to a
     * waiting request.
     */
    public static byte @NotNull [] push(@NotNull String module, @NotNull String payload) {
        return encode(module, 0, Reply.ok(payload));
    }

    /** Writes an answer to the request with this module and id. */
    public static byte @NotNull [] encode(@NotNull String module, int id, @NotNull Reply reply) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(module);
            out.writeInt(id);
            out.writeByte(reply.status().code());
            out.writeUTF(reply.detail());
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalArgumentException("Reply too long for the wire: " + module, impossible);
        }
    }
}
