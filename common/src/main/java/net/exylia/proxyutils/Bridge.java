package net.exylia.proxyutils;

import net.exylia.proxyutils.module.CommandsModule;
import net.exylia.proxyutils.module.ConnectModule;
import net.exylia.proxyutils.module.KickModule;
import net.exylia.proxyutils.module.PingModule;
import net.exylia.proxyutils.module.PlayerModule;
import net.exylia.proxyutils.module.PlayersModule;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Routes what a backend server asks to the module that answers it.
 *
 * <p>Every request is answered, whatever happens to it: a module that is not
 * there and a module that throws both go back as a status the server can act
 * on, because a request that is silently dropped is a five-second wait on the
 * other side and no idea why.
 */
public final class Bridge {

    private final Platform platform;
    private final Map<String, BridgeModule> modules = new ConcurrentHashMap<>();

    public Bridge(@NotNull Platform platform) {
        this.platform = platform;
    }

    /**
     * A bridge with the modules ExyliaLib expects: {@code ping},
     * {@code commands}, {@code connect}, {@code player}, {@code players} and {@code kick}.
     */
    public static @NotNull Bridge withBuiltIns(@NotNull Platform platform, @NotNull Publisher publisher) {
        Bridge bridge = new Bridge(platform);
        bridge.register(new PingModule(platform));
        bridge.register(new CommandsModule(platform));
        bridge.register(new ConnectModule(platform, publisher));
        bridge.register(new PlayerModule(platform));
        bridge.register(new PlayersModule(platform));
        bridge.register(new KickModule(platform));
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
     * Handles one request and hands the answer to whoever sends it back.
     *
     * <p>Called on the transport's thread; the answer is given on whichever
     * thread the module completes on.
     *
     * @param request what was asked, and by whom
     * @param answer  where the reply goes
     */
    public void receive(@NotNull Request request, @NotNull Consumer<Reply> answer) {
        BridgeModule module = modules.get(request.module().toLowerCase(Locale.ROOT));
        CompletableFuture<Reply> pending;
        if (module == null) {
            pending = CompletableFuture.completedFuture(Reply.unknownModule(request.module()));
        } else {
            try {
                pending = module.handle(request);
            } catch (RuntimeException failure) {
                pending = CompletableFuture.completedFuture(Reply.failed(failure));
            }
        }
        pending.exceptionally(Reply::failed).thenAccept(reply -> {
            try {
                answer.accept(reply);
            } catch (RuntimeException failure) {
                platform.warn("Could not answer " + request.module() + " for " + request.server()
                        + ": " + failure.getMessage());
            }
        });
    }
}
