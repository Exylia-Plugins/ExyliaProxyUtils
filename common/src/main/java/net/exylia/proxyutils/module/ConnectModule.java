package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Publisher;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Moves a player to another server, says whether it happened, and can hand
 * the destination server a note for when they get there.
 *
 * <p>The payload is {@code <server>|<uuid>|<memo>}, the last two optional:
 * an empty server means the one the request came from, an empty uuid means
 * the player the request is about, and a memo is sent to the destination
 * server as an {@code arrive} push once the proxy has connected them. That
 * last part is what lets a teleport across the network land <em>next to
 * somebody</em> rather than at the spawn: the server that asked writes
 * where, the proxy carries it, the server that receives them reads it — and
 * holds it until they have actually joined, since the push can win the race.
 *
 * <p>Server names on the proxy are the backends' {@code server-id}s: that is
 * what the memo is addressed by, and what an empty server resolves to.
 */
public final class ConnectModule implements BridgeModule {

    /** The push a memo travels as. */
    public static final String ARRIVE = "arrive";

    private final Platform platform;
    private final Publisher publisher;

    public ConnectModule(@NotNull Platform platform, @NotNull Publisher publisher) {
        this.platform = platform;
        this.publisher = publisher;
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
        if (who == null) {
            return CompletableFuture.completedFuture(Reply.rejected("nobody to move: no uuid given"));
        }
        String memo = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        String target = server;
        UUID moved = who;
        return platform.connect(who, server).thenApply(reply -> {
            if (reply.status() == Reply.Status.OK && memo != null) {
                publisher.push(target, ARRIVE, moved, memo);
            }
            return reply;
        });
    }
}
