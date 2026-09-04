package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Finds a player anywhere on the network.
 *
 * <p>The payload is a name or a uuid. A backend only knows the players it has
 * seen; the proxy knows every one that is connected, which is what a staff
 * member typing {@code /tp <name>} for somebody on another server needs.
 * Answers {@code OK} with {@code uuid|name|server}, or {@code REJECTED} for
 * a player who is not on the network.
 */
public final class PlayerModule implements BridgeModule {

    private final Platform platform;

    public PlayerModule(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    public @NotNull String name() {
        return "player";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        String who = request.payload().trim();
        if (who.isEmpty()) {
            return CompletableFuture.completedFuture(Reply.rejected("expected a name or a uuid"));
        }
        return CompletableFuture.completedFuture(platform.find(who)
                .map(found -> Reply.ok(found.toWire()))
                .orElseGet(() -> Reply.rejected("\"" + who + "\" is not on the network")));
    }
}
