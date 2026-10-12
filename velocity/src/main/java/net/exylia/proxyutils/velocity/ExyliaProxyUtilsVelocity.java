package net.exylia.proxyutils.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.exylia.proxyutils.BridgeConfig;
import net.exylia.proxyutils.RedisBridge;
import net.exylia.proxyutils.Updater;
import net.exylia.proxyutils.punish.BanManagerSource;
import net.exylia.proxyutils.punish.LibertyBansSource;
import net.exylia.proxyutils.punish.LiteBansSource;
import net.exylia.proxyutils.punish.Punishments;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * The Velocity half: reads the config, opens the Redis bridge, hooks the ban plugins it finds,
 * turns away players ExyliaStaff has banned, and closes it all.
 */
@Plugin(id = "exyliaproxyutils", name = "ExyliaProxyUtils", version = BuildConstants.VERSION,
        description = "The proxy half of ExyliaLib, over Redis: commands, teleports, lookups, punishments.",
        authors = {"Exylia"},
        // Optional: started after these when present, so their APIs are up when the hooks look.
        dependencies = {@Dependency(id = "litebans", optional = true),
                @Dependency(id = "libertybans", optional = true),
                @Dependency(id = "banmanager", optional = true)})
public final class ExyliaProxyUtilsVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path folder;
    private RedisBridge bridge;
    private VelocityPlatform updates;

    @Inject
    public ExyliaProxyUtilsVelocity(ProxyServer server, Logger logger, @DataDirectory Path folder) {
        this.server = server;
        this.logger = logger;
        this.folder = folder;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            BridgeConfig config = BridgeConfig.load(folder);
            VelocityPlatform platform = new VelocityPlatform(server, logger);
            if (config.updates()) {
                updates = platform;
                server.getScheduler().buildTask(this, () -> Updater.check(platform)).schedule();
            }
            bridge = RedisBridge.start(platform, config);
            Punishments punishments = bridge.punishments();
            punishments.install(LiteBansSource.ORIGIN, () -> LiteBansSource.install(platform, punishments.stream()));
            punishments.install(LibertyBansSource.ORIGIN,
                    () -> LibertyBansSource.install(platform, punishments.stream()));
            punishments.install(BanManagerSource.ORIGIN,
                    () -> BanManagerVelocity.install(this, server, punishments.stream()));
            logger.info("ExyliaProxyUtils {} ready on Redis {}:{}; modules: {}", BuildConstants.VERSION,
                    config.host(), config.port(), bridge.modules());
        } catch (Exception failure) {
            logger.error("ExyliaProxyUtils could not start; check {}/config.properties: {}",
                    folder, failure.getMessage());
        }
    }

    /** Off the network thread: the mirror is a Redis round trip. */
    @Subscribe(order = PostOrder.EARLY)
    public EventTask onLogin(LoginEvent event) {
        if (bridge == null || !event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            InetSocketAddress socket = event.getPlayer().getRemoteAddress();
            String address = socket.getAddress() == null ? null : socket.getAddress().getHostAddress();
            bridge.mirror().check(event.getPlayer().getUniqueId(), address).ifPresent(denial ->
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            VelocityPlatform.screen(denial.mini(), denial.legacy()))));
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (bridge != null) {
            bridge.close();
        }
        // Inline: a release published while the proxy ran is installed before it exits.
        if (updates != null) {
            Updater.check(updates);
        }
    }
}
