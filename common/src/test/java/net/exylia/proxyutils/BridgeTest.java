package net.exylia.proxyutils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());

    /** A proxy that remembers what it was asked and answers "found" unless told otherwise. */
    static final class FakePlatform implements Platform {
        final List<String> ran = new ArrayList<>();
        final List<byte[]> replies = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        boolean found = true;
        boolean connected = true;

        @Override public String name() { return "Fake"; }
        @Override public String version() { return "9.9.9"; }
        @Override public void info(String message) { }
        @Override public void warn(String message) { warnings.add(message); }

        @Override
        public CompletableFuture<Boolean> runAsPlayer(UUID player, String command) {
            ran.add("player " + player + ": " + command);
            return CompletableFuture.completedFuture(found);
        }

        @Override
        public CompletableFuture<Boolean> runAsConsole(String command) {
            ran.add("console: " + command);
            return CompletableFuture.completedFuture(found);
        }

        @Override
        public java.util.Optional<ProxyPlayer> find(String nameOrId) {
            boolean steve = nameOrId.equalsIgnoreCase("steve") || nameOrId.equals(STEVE.toString());
            return steve ? java.util.Optional.of(new ProxyPlayer(STEVE, "Steve", "lobby"))
                    : java.util.Optional.empty();
        }

        @Override
        public CompletableFuture<Reply> connect(UUID player, String server) {
            ran.add("connect " + player + " -> " + server);
            return CompletableFuture.completedFuture(
                    found ? Reply.ok() : Reply.rejected("no server \"" + server + "\""));
        }

        @Override
        public boolean reply(UUID carrier, byte[] data) {
            if (connected) {
                replies.add(data);
            }
            return connected;
        }
    }

    record Answer(String module, int id, int status, String detail) { }

    static byte[] request(String module, int id, String payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(module);
        out.writeInt(id);
        out.writeUTF(payload);
        return bytes.toByteArray();
    }

    static Answer answer(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        return new Answer(in.readUTF(), in.readInt(), in.readUnsignedByte(), in.readUTF());
    }

    @Test
    @DisplayName("ping answers with the plugin and the proxy, under the same id")
    void ping() throws IOException {
        FakePlatform proxy = new FakePlatform();
        Bridge.withBuiltIns(proxy).receive(STEVE, "Steve", "lobby", request("ping", 41, ""));
        assertEquals(new Answer("ping", 41, 0, "ExyliaProxyUtils 9.9.9 on Fake"),
                answer(proxy.replies.get(0)));
    }

    @Test
    @DisplayName("a console-proxy line runs as the console, a player-proxy line as the carrier")
    void commands() throws IOException {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy);
        bridge.receive(STEVE, "Steve", "lobby", request("commands", 1, "console-proxy: alert hi"));
        bridge.receive(STEVE, "Steve", "lobby", request("commands", 2, "player-proxy:/server hub"));
        assertEquals(List.of("console: alert hi", "player " + STEVE + ": server hub"), proxy.ran);
        assertEquals(0, answer(proxy.replies.get(0)).status());
        assertEquals(2, answer(proxy.replies.get(1)).id());
    }

    @Test
    @DisplayName("a command the proxy does not have is rejected by name, not reported as run")
    void unknownCommand() throws IOException {
        FakePlatform proxy = new FakePlatform();
        proxy.found = false;
        Bridge.withBuiltIns(proxy).receive(STEVE, "Steve", "lobby",
                request("commands", 3, "player-proxy:nosuchthing arg"));
        Answer reply = answer(proxy.replies.get(0));
        assertEquals(1, reply.status());
        assertTrue(reply.detail().contains("/nosuchthing"), reply.detail());
    }

    @Test
    @DisplayName("connect moves the carrier, or the player named after the pipe, and names a missing server")
    void connect() throws IOException {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy);
        UUID alex = UUID.nameUUIDFromBytes("alex".getBytes());
        bridge.receive(STEVE, "Steve", "lobby", request("connect", 7, "arena-1"));
        bridge.receive(STEVE, "Steve", "lobby", request("connect", 8, "lobby|" + alex));
        bridge.receive(STEVE, "Steve", "lobby", request("connect", 9, "lobby|not-a-uuid"));
        assertEquals(List.of("connect " + STEVE + " -> arena-1", "connect " + alex + " -> lobby"), proxy.ran);
        assertEquals(0, answer(proxy.replies.get(0)).status());
        assertEquals(1, answer(proxy.replies.get(2)).status());

        proxy.found = false;
        bridge.receive(STEVE, "Steve", "lobby", request("connect", 10, "arena-9"));
        Answer reply = answer(proxy.replies.get(3));
        assertEquals(1, reply.status());
        assertTrue(reply.detail().startsWith("no server"), reply.detail());
    }

    @Test
    @DisplayName("player finds by name or id and answers uuid|name|server, or rejects")
    void player() throws IOException {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy);
        bridge.receive(STEVE, "Steve", "lobby", request("player", 11, "STEVE"));
        bridge.receive(STEVE, "Steve", "lobby", request("player", 12, STEVE.toString()));
        bridge.receive(STEVE, "Steve", "lobby", request("player", 13, "Nobody"));
        assertEquals(new Answer("player", 11, 0, STEVE + "|Steve|lobby"), answer(proxy.replies.get(0)));
        assertEquals(0, answer(proxy.replies.get(1)).status());
        assertEquals(1, answer(proxy.replies.get(2)).status());
    }

    @Test
    @DisplayName("a bad actor and a missing module are answered, never dropped")
    void refusals() throws IOException {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy);
        bridge.receive(STEVE, "Steve", "lobby", request("commands", 4, "root:rm -rf"));
        bridge.receive(STEVE, "Steve", "lobby", request("teleport", 5, "x"));
        assertEquals(List.of(), proxy.ran);
        assertEquals(1, answer(proxy.replies.get(0)).status());
        assertEquals(3, answer(proxy.replies.get(1)).status());
    }

    @Test
    @DisplayName("a module that throws answers FAILED, and unreadable bytes are only logged")
    void failures() throws IOException {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = new Bridge(proxy);
        bridge.register(new BridgeModule() {
            @Override public String name() { return "Boom"; }
            @Override public CompletableFuture<Reply> handle(Request request) {
                throw new IllegalStateException("kaboom");
            }
        });
        bridge.receive(STEVE, "Steve", "lobby", request("boom", 6, ""));
        assertEquals(new Answer("boom", 6, 2, "kaboom"), answer(proxy.replies.get(0)));

        bridge.receive(STEVE, "Steve", "lobby", new byte[] {1, 2, 3});
        assertEquals(1, proxy.replies.size());
        assertEquals(1, proxy.warnings.size());
    }
}
