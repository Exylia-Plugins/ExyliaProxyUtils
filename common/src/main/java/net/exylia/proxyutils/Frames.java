package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The strings on the Redis channels, in both directions.
 *
 * <p>A backend publishes requests on {@code <prefix>:bridge:proxy} as
 * {@code <server-id>|<carrier uuid or empty>|<module>|<id>|<payload>}; the
 * proxy answers on {@code <prefix>:bridge:<server-id>} as
 * {@code <module>|<id>|<status>|<carrier uuid or empty>|<detail>}, and sends
 * what it has to say unasked on the same channel with id 0. The payload and
 * the detail come last and may contain pipes. ExyliaLib
 * ({@code net.exylia.lib.proxy.internal.Frames}) reads and writes exactly
 * this, so a change here is a change there.
 */
public final class Frames {

    /** What every channel of the bridge starts with, after the network's prefix. */
    public static final String INFIX = ":bridge:";

    /** The channel the proxy listens on, after the network's prefix. */
    public static final String PROXY = "proxy";

    private Frames() {
        throw new AssertionError("No instances.");
    }

    /** One request, as a backend sent it. */
    public record Incoming(@NotNull String server, @Nullable UUID carrier, @NotNull String module,
                           int id, @NotNull String payload) {
    }

    /**
     * Reads a request.
     *
     * @throws IllegalArgumentException if the text is not one; the caller drops it
     */
    public static @NotNull Incoming decode(@NotNull String raw) {
        String[] parts = raw.split("\\|", 5);
        if (parts.length != 5 || parts[0].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("expected server|carrier|module|id|payload");
        }
        UUID carrier = parts[1].isEmpty() ? null : UUID.fromString(parts[1]);
        return new Incoming(parts[0], carrier, parts[2], Integer.parseInt(parts[3]), parts[4]);
    }

    /** Writes an answer to the request with this module and id. */
    public static @NotNull String encode(@NotNull String module, int id, @Nullable UUID carrier,
                                         @NotNull Reply reply) {
        return module + '|' + id + '|' + reply.status().code() + '|'
                + (carrier == null ? "" : carrier) + '|' + reply.detail();
    }

    /** Writes something the proxy says unasked: an answer with id 0. */
    public static @NotNull String push(@NotNull String module, @Nullable UUID carrier,
                                       @NotNull String payload) {
        return encode(module, 0, carrier, Reply.ok(payload));
    }

    /** The channel a server's answers and pushes go on. */
    public static @NotNull String channelOf(@NotNull String prefix, @NotNull String server) {
        return prefix + INFIX + server;
    }
}
