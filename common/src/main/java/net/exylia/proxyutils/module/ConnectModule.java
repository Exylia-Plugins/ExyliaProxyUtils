package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Moves a player to another server, says whether it happened, and can hand
 * the destination server a note once they are there.
 *
 * <p>The payload is {@code <server>|<uuid>|<memo>}, the last two optional:
 * an empty server means the one the request came from, an empty uuid means
 * the player carrying the request, and a memo is delivered to the destination
 * server as an {@code arrive} push through the moved player the moment they
 * have joined it. That last part is what lets a teleport across the network
 * land <em>next to somebody</em> rather than at the spawn, with no Redis in
 * between: the server that asked writes where, the proxy carries it, the
 * server that receives them reads it.
 *
 * <p>{@code Connect} and {@code ConnectOther} on the {@code BungeeCord}
 * channel do the moving part of this and never answer: a server name with a
 * typo in it sends the player nowhere and tells nobody. Here it is
 * {@link Reply.Status#REJECTED} with {@code no server "<name>"}.
 */
public final class ConnectModule implements BridgeModule {

    private final Platform platform;

    public ConnectModule(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    public @NotNull String name() {
        return "connect";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        String[] parts = request.payload().split("\\|", 3);
        String server = parts[0].trim();
        if (server.isEmpty()) {
            server = request.server();
        }
        UUID who = request.carrier();
        if (parts.length > 1 && !parts[1].isBlank()) {
            try {
                who = UUID.fromString(parts[1].trim());
            } catch (IllegalArgumentException unreadable) {
                return CompletableFuture.completedFuture(Reply.rejected("not a player id: " + parts[1]));
            }
        }
        String memo = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        return platform.connect(who, server, memo);
    }
}
