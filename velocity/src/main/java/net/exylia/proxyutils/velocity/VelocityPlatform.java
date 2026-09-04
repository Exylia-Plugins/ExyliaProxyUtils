package net.exylia.proxyutils.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import net.exylia.proxyutils.Reply;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Velocity, seen through {@link Platform}. */
final class VelocityPlatform implements Platform {

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
    public @NotNull Collection<String> playerNames() {
        return server.getAllPlayers().stream().map(Player::getUsername).toList();
    }

    @Override
    public @NotNull Optional<ProxyPlayer> find(@NotNull String nameOrId) {
        Optional<Player> who;
        try {
            who = server.getPlayer(UUID.fromString(nameOrId));
        } catch (IllegalArgumentException notAnId) {
            who = server.getPlayer(nameOrId);
        }
        return who.map(player -> new ProxyPlayer(player.getUniqueId(), player.getUsername(),
                player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("")));
    }

    @Override
    public @NotNull CompletableFuture<Reply> connect(@NotNull UUID player, @NotNull String name) {
        RegisteredServer target = server.getServer(name).orElse(null);
        if (target == null) {
            return CompletableFuture.completedFuture(Reply.rejected("no server \"" + name + "\""));
        }
        Player who = server.getPlayer(player).orElse(null);
        if (who == null) {
            return CompletableFuture.completedFuture(Reply.rejected("player is not connected"));
        }
        return who.createConnectionRequest(target).connect().thenApply(result -> switch (result.getStatus()) {
            case SUCCESS -> Reply.ok();
            case ALREADY_CONNECTED -> Reply.ok("already there");
            case CONNECTION_IN_PROGRESS -> Reply.rejected("already connecting somewhere");
            case CONNECTION_CANCELLED -> Reply.rejected("a proxy plugin cancelled the connection");
            case SERVER_DISCONNECTED -> Reply.rejected("server \"" + name + "\" refused the connection");
        });
    }
}
