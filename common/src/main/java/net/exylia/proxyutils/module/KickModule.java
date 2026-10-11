package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Disconnects a player from the whole network, wherever they are.
 *
 * <p>The payload is {@code <uuid>|<mini>|<legacy>}, split on the first two pipes so the legacy
 * screen may hold more: Velocity shows the MiniMessage screen, BungeeCord the §-coded one. What
 * ExyliaStaff uses to kick or ban a player who is on another server. Answers {@code OK} once
 * disconnected, {@code REJECTED not connected} for a player the proxy does not have.
 */
public final class KickModule implements BridgeModule {

    private final Platform platform;

    public KickModule(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    public @NotNull String name() {
        return "kick";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        String[] parts = request.payload().split("\\|", 3);
        UUID who;
        try {
            who = UUID.fromString(parts[0].trim());
        } catch (IllegalArgumentException unreadable) {
            return CompletableFuture.completedFuture(Reply.rejected("not a player id: " + parts[0]));
        }
        String mini = parts.length > 1 ? parts[1] : "";
        String legacy = parts.length > 2 ? parts[2] : "";
        return CompletableFuture.completedFuture(platform.disconnect(who, mini, legacy)
                ? Reply.ok() : Reply.rejected("not connected"));
    }
}
