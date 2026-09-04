package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Answers "who is there".
 *
 * <p>ExyliaLib sends one through the first player to join a server and prints
 * the answer, which is how an owner learns the bridge is installed and which
 * version; a server that never gets an answer says so instead.
 */
public final class PingModule implements BridgeModule {

    private final Platform platform;

    public PingModule(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    public @NotNull String name() {
        return "ping";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        return CompletableFuture.completedFuture(
                Reply.ok("ExyliaProxyUtils " + platform.version() + " on " + platform.name()));
    }
}
