package net.exylia.proxyutils.module;

import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import net.exylia.proxyutils.ServerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServersModuleTest {

    @Test
    @DisplayName("servers answers name,players,up for each server and pings once per TTL")
    void cachedRound() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        Platform platform = new Platform() {
            @Override public String name() { return "Fake"; }
            @Override public String version() { return "1"; }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public CompletableFuture<Boolean> runAsPlayer(UUID player, String command) { return null; }
            @Override public CompletableFuture<Boolean> runAsConsole(String command) { return null; }
            @Override public Collection<String> playerNames() { return List.of(); }
            @Override public Optional<ProxyPlayer> find(String nameOrId) { return Optional.empty(); }
            @Override public CompletableFuture<Reply> connect(UUID player, String server) { return null; }
            @Override public boolean disconnect(UUID player, String mini, String legacy) { return false; }
            @Override public CompletableFuture<List<ServerStatus>> servers(Duration timeout) {
                rounds.incrementAndGet();
                return CompletableFuture.completedFuture(List.of(
                        new ServerStatus("lobby", 12, true), new ServerStatus("bed,wars", 0, false)));
            }
        };
        ServersModule module = new ServersModule(platform, now::get);
        Request request = new Request("servers", "", null, "", "lobby");

        assertEquals(Reply.ok("lobby,12,1;bed,wars,0,0"), module.handle(request).join());
        now.addAndGet(ServersModule.TTL.toNanos() - 1);
        module.handle(request).join();
        assertEquals(1, rounds.get());
        now.addAndGet(1);
        module.handle(request).join();
        assertEquals(2, rounds.get());
    }
}
