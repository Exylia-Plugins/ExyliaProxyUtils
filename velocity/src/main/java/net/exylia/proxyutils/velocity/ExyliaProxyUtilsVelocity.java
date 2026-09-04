package net.exylia.proxyutils.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.exylia.proxyutils.BridgeConfig;
import net.exylia.proxyutils.RedisBridge;
import org.slf4j.Logger;

import java.nio.file.Path;

/** The Velocity half: reads the config, opens the Redis bridge, closes it. */
@Plugin(id = "exyliaproxyutils", name = "ExyliaProxyUtils", version = BuildConstants.VERSION,
        description = "The proxy half of ExyliaLib, over Redis: commands, teleports, lookups.",
        authors = {"Exylia"})
public final class ExyliaProxyUtilsVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path folder;
    private RedisBridge bridge;

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
            bridge = RedisBridge.start(new VelocityPlatform(server, logger), config);
            logger.info("ExyliaProxyUtils {} ready on Redis {}:{}; modules: {}", BuildConstants.VERSION,
                    config.host(), config.port(), bridge.modules());
        } catch (Exception failure) {
            logger.error("ExyliaProxyUtils could not start; check {}/config.properties: {}",
                    folder, failure.getMessage());
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (bridge != null) {
            bridge.close();
        }
    }
}
