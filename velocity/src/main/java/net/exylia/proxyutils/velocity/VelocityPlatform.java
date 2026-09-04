package net.exylia.proxyutils.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Wire;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Velocity, seen through {@link Platform}. */
final class VelocityPlatform implements Platform {

    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(Wire.CHANNEL);

    private final ProxyServer server;
    private final Logger logger;

    VelocityPlatform(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Override
    public @NotNull String name() {
        return "Velocity";
    }

    @Override
    public @NotNull String version() {
        return BuildConstants.VERSION;
    }

    @Override
    public void info(@NotNull String message) {
        logger.info(message);
    }

    @Override
    public void warn(@NotNull String message) {
        logger.warn(message);
    }

    /** Through the command manager's event path, so other plugins see it as a real command. */
    @Override
    public @NotNull CompletableFuture<Boolean> runAsPlayer(@NotNull UUID player,
                                                           @NotNull String command) {
        return server.getPlayer(player)
                .map(who -> server.getCommandManager().executeAsync(who, command))
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> runAsConsole(@NotNull String command) {
        return server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
    }

    @Override
    public boolean reply(@NotNull UUID carrier, byte @NotNull [] data) {
        return server.getPlayer(carrier)
                .flatMap(Player::getCurrentServer)
                .map(connection -> connection.sendPluginMessage(CHANNEL, data))
                .orElse(false);
    }
}
