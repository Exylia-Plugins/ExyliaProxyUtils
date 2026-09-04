package net.exylia.proxyutils;

import net.exylia.proxyutils.module.CommandsModule;
import net.exylia.proxyutils.module.ConnectModule;
import net.exylia.proxyutils.module.PingModule;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes what a backend server asks to the module that answers it.
 *
 * <p>Every request is answered, whatever happens to it: a module that is not
 * there, a module that throws, a payload that cannot be read all go back as
 * a status the server can act on, because a request that is silently dropped
 * is a five-second wait on the other side and no idea why.
 */
public final class Bridge {

    private final Platform platform;
    private final Map<String, BridgeModule> modules = new ConcurrentHashMap<>();

    public Bridge(@NotNull Platform platform) {
        this.platform = platform;
    }

    /** A bridge with the modules ExyliaLib expects: {@code ping}, {@code commands} and {@code connect}. */
    public static @NotNull Bridge withBuiltIns(@NotNull Platform platform) {
        Bridge bridge = new Bridge(platform);
        bridge.register(new PingModule(platform));
        bridge.register(new CommandsModule(platform));
        bridge.register(new ConnectModule(platform));
        return bridge;
    }

    /** Adds a module; a later one with the same name replaces the earlier. */
    public void register(@NotNull BridgeModule module) {
        String name = module.name().trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A module needs a name.");
        }
        modules.put(name, module);
    }

    /** The names servers can address, sorted. */
    public @NotNull Set<String> modules() {
        return new TreeSet<>(modules.keySet());
    }

    /**
     * Handles bytes that arrived from a backend server through a player.
     *
     * <p>Called on the proxy's network thread; the answer is sent from
     * whichever thread the module completes on.
     *
     * @param carrier     the player whose connection carried them
     * @param carrierName their name
     * @param server      the backend server they came from
     * @param data        the bytes
     */
    public void receive(@NotNull UUID carrier, @NotNull String carrierName,
                        @NotNull String server, byte @NotNull [] data) {
        Wire.Frame frame;
        try {
            frame = Wire.decode(data);
        } catch (IOException | RuntimeException unreadable) {
            // Nothing to answer to: without a module and an id there is no
            // request to fail. Said once per bad message, since a server that
            // speaks a different protocol will keep doing it.
            platform.warn("Dropped unreadable bytes on " + Wire.CHANNEL + " from " + server
                    + " through " + carrierName + ": " + unreadable.getMessage());
            return;
        }
        Request request = new Request(frame.module(), frame.payload(), carrier, carrierName, server);
        BridgeModule module = modules.get(frame.module().toLowerCase(Locale.ROOT));
        CompletableFuture<Reply> answer;
        if (module == null) {
            answer = CompletableFuture.completedFuture(Reply.unknownModule(frame.module()));
        } else {
            try {
                answer = module.handle(request);
            } catch (RuntimeException failure) {
                answer = CompletableFuture.completedFuture(Reply.failed(failure));
            }
        }
        answer.exceptionally(Reply::failed).thenAccept(reply -> {
            if (!platform.reply(carrier, Wire.encode(frame.module(), frame.id(), reply))) {
                platform.warn("Could not answer " + frame.module() + " #" + frame.id() + " for "
                        + server + ": " + carrierName + " is no longer connected to it.");
            }
        });
    }
}
