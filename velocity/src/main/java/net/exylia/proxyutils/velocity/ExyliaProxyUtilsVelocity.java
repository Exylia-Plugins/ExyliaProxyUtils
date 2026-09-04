package net.exylia.proxyutils.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import net.exylia.proxyutils.Bridge;
import org.slf4j.Logger;

/**
 * The Velocity half.
 *
 * <p>Registers the channel, hands every message on it to the {@link Bridge},
 * and keeps the channel off the client in both directions.
 */
@Plugin(id = "exyliaproxyutils", name = "ExyliaProxyUtils", version = BuildConstants.VERSION,
        description = "The proxy half of ExyliaLib: commands, and whatever comes next, over one channel.",
        authors = {"Exylia"})
public final class ExyliaProxyUtilsVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private Bridge bridge;

    @Inject
    public ExyliaProxyUtilsVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        // Without this Velocity forwards the channel to the client untouched
        // and never fires the event.
        server.getChannelRegistrar().register(VelocityPlatform.CHANNEL);
        bridge = Bridge.withBuiltIns(new VelocityPlatform(server, logger));
        logger.info("ExyliaProxyUtils {} ready; modules: {}", BuildConstants.VERSION, bridge.modules());
    }

    @Subscribe
    public void onMessage(PluginMessageEvent event) {
        if (!VelocityPlatform.CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // Ours in both directions: a client never sees bridge traffic, and
        // bytes a client wrote on this channel never reach a module — the
        // source check below is what stops a modified client from running
        // console-proxy commands.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection from)) {
            return;
        }
        bridge.receive(from.getPlayer().getUniqueId(), from.getPlayer().getUsername(),
                from.getServerInfo().getName(), event.getData());
    }
}
