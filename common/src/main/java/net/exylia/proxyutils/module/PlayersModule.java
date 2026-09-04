package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Every name connected to the network, comma-separated.
 *
 * <p>What a backend needs to tab-complete {@code /tp <name>} with players it
 * has never seen. ExyliaLib asks every few seconds through whoever is online
 * and keeps the answer, since a tab completion cannot wait for a round trip.
 */
public final class PlayersModule implements BridgeModule {

    private final Platform platform;

    public PlayersModule(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    public @NotNull String name() {
        return "players";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        return CompletableFuture.completedFuture(Reply.ok(String.join(",", platform.playerNames())));
    }
}
