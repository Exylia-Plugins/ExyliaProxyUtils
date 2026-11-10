package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import net.exylia.proxyutils.ServerStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Every registered backend with its player count and whether it answers.
 *
 * <p>Answers {@code name,players,up;name,players,up}, {@code up} being
 * {@code 1} or {@code 0}. A name may hold commas, so a reader takes the last two
 * fields of each entry. Pinging every server for every backend that asks would
 * multiply with the network, so one round of pings is kept for {@link #TTL} and
 * every request in that window shares it.
 */
public final class ServersModule implements BridgeModule {

    /** How long one round of pings answers for. */
    static final Duration TTL = Duration.ofSeconds(3);
    /** How long a server may take to answer a ping before it counts as offline. */
    static final Duration PING_TIMEOUT = Duration.ofMillis(1500);

    private final Platform platform;
    private final LongSupplier clock;
    private CompletableFuture<String> cached;
    private long cachedAt;

    public ServersModule(@NotNull Platform platform) {
        this(platform, System::nanoTime);
    }

    ServersModule(@NotNull Platform platform, @NotNull LongSupplier clock) {
        this.platform = platform;
        this.clock = clock;
    }

    @Override
    public @NotNull String name() {
        return "servers";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        return round().thenApply(Reply::ok);
    }

    private synchronized CompletableFuture<String> round() {
        long now = clock.getAsLong();
        if (cached == null || now - cachedAt >= TTL.toNanos()) {
            cachedAt = now;
            cached = platform.servers(PING_TIMEOUT)
                    .exceptionally(failure -> List.of())
                    .thenApply(ServersModule::toWire);
        }
        return cached;
    }

    static @NotNull String toWire(@NotNull List<ServerStatus> servers) {
        StringBuilder out = new StringBuilder();
        for (ServerStatus server : servers) {
            if (!out.isEmpty()) out.append(';');
            out.append(server.name().replace(';', '_')).append(',')
                    .append(server.players()).append(',').append(server.online() ? '1' : '0');
        }
        return out.toString();
    }
}
