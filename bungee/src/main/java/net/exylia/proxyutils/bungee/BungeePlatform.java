package net.exylia.proxyutils.bungee;

import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Wire;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import org.jetbrains.annotations.NotNull;

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
    public boolean reply(@NotNull UUID carrier, byte @NotNull [] data) {
        ProxiedPlayer who = proxy.getPlayer(carrier);
        Server server = who == null ? null : who.getServer();
        if (server == null) {
            return false;
        }
        server.sendData(Wire.CHANNEL, data);
        return true;
    }
}
