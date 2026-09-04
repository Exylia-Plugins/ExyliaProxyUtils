package net.exylia.proxyutils.bungee;

import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import net.exylia.proxyutils.Reply;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** BungeeCord, seen through {@link Platform}. */
final class BungeePlatform implements Platform {

    private final ProxyServer proxy;
    private final Logger logger;
    private final String version;


    BungeePlatform(ProxyServer proxy, Logger logger, String version) {
        this.proxy = proxy;
        this.logger = logger;
        this.version = version;
    }

    @Override
    public @NotNull String name() {
        return "BungeeCord";
    }

    @Override
    public @NotNull String version() {
        return version;
    }

    @Override
    public void info(@NotNull String message) {
        logger.info(message);
    }

    @Override
    public void warn(@NotNull String message) {
        logger.warning(message);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> runAsPlayer(@NotNull UUID player,
                                                           @NotNull String command) {
        ProxiedPlayer who = proxy.getPlayer(player);
        return CompletableFuture.completedFuture(
                who != null && proxy.getPluginManager().dispatchCommand(who, command));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> runAsConsole(@NotNull String command) {
        return CompletableFuture.completedFuture(
                proxy.getPluginManager().dispatchCommand(proxy.getConsole(), command));
    }

    @Override
    public @NotNull Collection<String> playerNames() {
        return proxy.getPlayers().stream().map(ProxiedPlayer::getName).toList();
    }

    @Override
    public @NotNull Optional<ProxyPlayer> find(@NotNull String nameOrId) {
        ProxiedPlayer who;
        try {
            who = proxy.getPlayer(UUID.fromString(nameOrId));
        } catch (IllegalArgumentException notAnId) {
            who = proxy.getPlayer(nameOrId);
        }
        if (who == null) {
            return Optional.empty();
        }
        Server current = who.getServer();
        return Optional.of(new ProxyPlayer(who.getUniqueId(), who.getName(),
                current == null ? "" : current.getInfo().getName()));
    }

    @Override
    public @NotNull CompletableFuture<Reply> connect(@NotNull UUID player, @NotNull String name) {
        ServerInfo target = proxy.getServerInfo(name);
        if (target == null) {
            return CompletableFuture.completedFuture(Reply.rejected("no server \"" + name + "\""));
        }
        ProxiedPlayer who = proxy.getPlayer(player);
        if (who == null) {
            return CompletableFuture.completedFuture(Reply.rejected("player is not connected"));
        }
        Server current = who.getServer();
        if (current != null && current.getInfo().getName().equals(name)) {
            return CompletableFuture.completedFuture(Reply.ok("already there"));
        }
        CompletableFuture<Reply> done = new CompletableFuture<>();
        who.connect(target, (connected, error) -> {
            done.complete(error != null
                    ? Reply.rejected("server \"" + name + "\" refused the connection: " + error.getMessage())
                    : connected ? Reply.ok() : Reply.rejected("the proxy did not connect them"));
        });
        return done;
    }
}
