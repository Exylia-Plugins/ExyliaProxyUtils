package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Moves a player to another server, and says whether it happened.
 *
 * <p>The payload is {@code <server>} to move the player carrying the request,
 * or {@code <server>|<uuid>} to move somebody else — what {@code Connect} and
 * {@code ConnectOther} on the {@code BungeeCord} channel do, except that
 * those never answer: a server name with a typo in it sends the player
 * nowhere and tells nobody. Here it is {@link Reply.Status#REJECTED} with
 * {@code no server "<name>"}, which ExyliaLib prints.
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
        String payload = request.payload().trim();
        int pipe = payload.indexOf('|');
        String server = (pipe < 0 ? payload : payload.substring(0, pipe)).trim();
        if (server.isEmpty()) {
            return CompletableFuture.completedFuture(Reply.rejected("expected <server> or <server>|<uuid>"));
        }
        UUID who = request.carrier();
        if (pipe >= 0) {
            try {
                who = UUID.fromString(payload.substring(pipe + 1).trim());
            } catch (IllegalArgumentException unreadable) {
                return CompletableFuture.completedFuture(
                        Reply.rejected("not a player id: " + payload.substring(pipe + 1)));
            }
        }
        return platform.connect(who, server);
    }
}
