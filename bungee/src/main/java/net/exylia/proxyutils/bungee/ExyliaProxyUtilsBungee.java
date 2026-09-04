package net.exylia.proxyutils.bungee;

import net.exylia.proxyutils.Bridge;
import net.exylia.proxyutils.Wire;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * The BungeeCord half.
 *
 * <p>Registers the channel, hands every message on it to the {@link Bridge},
 * and keeps the channel off the client in both directions.
 */
public final class ExyliaProxyUtilsBungee extends Plugin implements Listener {

    private Bridge bridge;
    private BungeePlatform platform;

    @Override
    public void onEnable() {
        getProxy().registerChannel(Wire.CHANNEL);
        platform = new BungeePlatform(getProxy(), getLogger(), getDescription().getVersion());
        bridge = Bridge.withBuiltIns(platform);
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("ExyliaProxyUtils " + getDescription().getVersion() + " ready; modules: "
                + bridge.modules());
    }

    @Override
    public void onDisable() {
        getProxy().getPluginManager().unregisterListeners(this);
        getProxy().unregisterChannel(Wire.CHANNEL);
    }

    /** Delivers the memo of a {@code connect} once the player is on the new server. */
    @EventHandler
    public void onConnected(ServerConnectedEvent event) {
        byte[] memo = platform.takeArrival(event.getPlayer().getUniqueId());
        if (memo != null) {
            event.getServer().sendData(Wire.CHANNEL, memo);
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        platform.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMessage(PluginMessageEvent event) {
        if (!Wire.CHANNEL.equals(event.getTag())) {
            return;
        }
        // Ours in both directions: a client never sees bridge traffic, and
        // bytes a client wrote on this channel never reach a module — the
        // sender check below is what stops a modified client from running
        // console-proxy commands.
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server from)
                || !(event.getReceiver() instanceof ProxiedPlayer carrier)) {
            return;
        }
        bridge.receive(carrier.getUniqueId(), carrier.getName(), from.getInfo().getName(),
                event.getData());
    }
}
