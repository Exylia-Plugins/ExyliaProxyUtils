package net.exylia.proxyutils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());

    /** A proxy that remembers what it was asked and answers "found" unless told otherwise. */
    static final class FakePlatform implements Platform, Publisher {
        final List<String> ran = new ArrayList<>();
        final List<String> pushes = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        boolean found = true;

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
        public Collection<String> playerNames() {
            return List.of("Steve", "Alex");
        }

        @Override
        public Optional<ProxyPlayer> find(String nameOrId) {
            boolean steve = nameOrId.equalsIgnoreCase("steve") || nameOrId.equals(STEVE.toString());
            return steve ? Optional.of(new ProxyPlayer(STEVE, "Steve", "lobby")) : Optional.empty();
        }

        @Override
        public CompletableFuture<Reply> connect(UUID player, String server) {
            ran.add("connect " + player + " -> " + server);
            return CompletableFuture.completedFuture(
                    found ? Reply.ok() : Reply.rejected("no server \"" + server + "\""));
        }

        @Override
        public void push(String server, String module, UUID carrier, String payload) {
            pushes.add(server + " <- " + module + " " + carrier + " " + payload);
        }
    }

    /** Sends one request the way a backend would, and returns the reply. */
    static Reply ask(FakePlatform proxy, Bridge bridge, UUID carrier, String module, String payload) {
        List<Reply> replies = new ArrayList<>();
        bridge.receive(new Request(module, payload, carrier, carrier == null ? "" : "Steve", "lobby"),
                replies::add);
        assertEquals(1, replies.size(), "every request is answered exactly once");
        return replies.get(0);
    }

    @Test
    @DisplayName("frames: a request is server|carrier|module|id|payload, an answer module|id|status|carrier|detail")
    void frames() {
        Frames.Incoming in = Frames.decode("lobby|" + STEVE + "|commands|7|player-proxy:server a|b");
        assertEquals(new Frames.Incoming("lobby", STEVE, "commands", 7, "player-proxy:server a|b"), in,
                "the payload keeps its pipes");
        assertEquals(new Frames.Incoming("lobby", null, "players", 8, ""),
                Frames.decode("lobby||players|8|"), "an empty carrier is nobody");
        assertEquals("connect|3|1||no server \"x\"",
                Frames.encode("connect", 3, null, Reply.rejected("no server \"x\"")));
        assertEquals("arrive|0|0|" + STEVE + "|player:abc", Frames.push("arrive", STEVE, "player:abc"));
        assertThrows(IllegalArgumentException.class, () -> Frames.decode("garbage"));
        assertEquals("exylia:bridge:lobby", Frames.channelOf("exylia", "lobby"));
    }

    @Test
    @DisplayName("ping answers with the plugin and the proxy")
    void ping() {
        FakePlatform proxy = new FakePlatform();
        Reply reply = ask(proxy, Bridge.withBuiltIns(proxy, proxy), null, "ping", "");
        assertEquals(Reply.ok("ExyliaProxyUtils 9.9.9 on Fake"), reply);
    }

    @Test
    @DisplayName("a console-proxy line runs as the console, a player-proxy line as the carrier")
    void commands() {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy, proxy);
        assertTrue(ask(proxy, bridge, null, "commands", "console-proxy: alert hi").status() == Reply.Status.OK);
        assertTrue(ask(proxy, bridge, STEVE, "commands", "player-proxy:/server hub").status() == Reply.Status.OK);
        assertEquals(Reply.Status.REJECTED, ask(proxy, bridge, null, "commands", "player-proxy:server hub").status(),
                "a player command with nobody to run it as is refused");
        assertEquals(List.of("console: alert hi", "player " + STEVE + ": server hub"), proxy.ran);
    }

    @Test
    @DisplayName("a command the proxy does not have is rejected by name, not reported as run")
    void unknownCommand() {
        FakePlatform proxy = new FakePlatform();
        proxy.found = false;
        Reply reply = ask(proxy, Bridge.withBuiltIns(proxy, proxy), STEVE, "commands", "player-proxy:nosuchthing arg");
        assertEquals(Reply.Status.REJECTED, reply.status());
        assertTrue(reply.detail().contains("/nosuchthing"), reply.detail());
    }

    @Test
    @DisplayName("connect moves the carrier or the named player, and pushes the memo to the destination")
    void connect() {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy, proxy);
        assertEquals(Reply.Status.OK, ask(proxy, bridge, STEVE, "connect", "arena-1").status());
        assertEquals(Reply.Status.OK, ask(proxy, bridge, STEVE, "connect", "|" + ALEX + "|lobby,world,1,2,3").status());
        assertEquals(Reply.Status.REJECTED, ask(proxy, bridge, STEVE, "connect", "lobby|not-a-uuid").status());
        assertEquals(Reply.Status.REJECTED, ask(proxy, bridge, null, "connect", "lobby").status(),
                "nobody to move");
        assertEquals(List.of("connect " + STEVE + " -> arena-1", "connect " + ALEX + " -> lobby"), proxy.ran,
                "an empty server is the one the request came from");
        assertEquals(List.of("lobby <- arrive " + ALEX + " lobby,world,1,2,3"), proxy.pushes,
                "the memo goes to the destination, about the moved player, only on success");

        proxy.found = false;
        Reply reply = ask(proxy, bridge, STEVE, "connect", "arena-9|" + STEVE + "|memo");
        assertEquals(Reply.Status.REJECTED, reply.status());
        assertTrue(reply.detail().startsWith("no server"), reply.detail());
        assertEquals(1, proxy.pushes.size(), "no memo for a move that did not happen");
    }

    @Test
    @DisplayName("player finds by name or id and answers uuid|name|server; players lists everybody")
    void player() {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy, proxy);
        assertEquals(Reply.ok(STEVE + "|Steve|lobby"), ask(proxy, bridge, null, "player", "STEVE"));
        assertEquals(Reply.Status.OK, ask(proxy, bridge, null, "player", STEVE.toString()).status());
        assertEquals(Reply.Status.REJECTED, ask(proxy, bridge, null, "player", "Nobody").status());
        assertEquals("Steve,Alex", ask(proxy, bridge, null, "players", "").detail());
    }

    @Test
    @DisplayName("a bad actor, a missing module and a module that throws are answered, never dropped")
    void refusals() {
        FakePlatform proxy = new FakePlatform();
        Bridge bridge = Bridge.withBuiltIns(proxy, proxy);
        bridge.register(new BridgeModule() {
            @Override public String name() { return "Boom"; }
            @Override public CompletableFuture<Reply> handle(Request request) {
                throw new IllegalStateException("kaboom");
            }
        });
        assertEquals(Reply.Status.REJECTED, ask(proxy, bridge, STEVE, "commands", "root:rm -rf").status());
        assertEquals(Reply.Status.UNKNOWN_MODULE, ask(proxy, bridge, STEVE, "teleport", "x").status());
        assertEquals(new Reply(Reply.Status.FAILED, "kaboom"), ask(proxy, bridge, STEVE, "boom", ""));
        assertEquals(List.of(), proxy.ran);
    }
}
