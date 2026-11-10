package net.exylia.proxyutils.wire;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * What the proxy needs from the network's shared store: Redis, or the
 * backends' own database when they run {@code cross-server: database}.
 *
 * <p>Every call may block on the network and may throw; callers already run
 * off the proxy's network threads and decide what a failure means.
 */
public interface Wire extends AutoCloseable {

    /** Sends a message to every listener of a channel. */
    void publish(@NotNull String channel, @NotNull String message);

    /**
     * Starts listening on one channel, on a thread of the wire's own, until
     * {@link #close}. Returns once listening has started.
     */
    void listen(@NotNull String channel, @NotNull Consumer<String> handler);

    /** A key's value, or {@code null} when absent or expired. */
    @Nullable String get(@NotNull String key);

    /** Appends to a stream, trimming it to about {@code maxLength} entries. */
    void streamAdd(@NotNull String stream, @NotNull Map<String, String> fields, long maxLength);

    /** What this wire is, for the log. */
    @NotNull String describe();

    @Override
    void close();
}
