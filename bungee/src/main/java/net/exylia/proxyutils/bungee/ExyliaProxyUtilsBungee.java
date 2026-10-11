package net.exylia.proxyutils.bungee;

import net.exylia.proxyutils.BridgeConfig;
import net.exylia.proxyutils.RedisBridge;
import net.exylia.proxyutils.punish.BanManagerSource;
import net.exylia.proxyutils.punish.LibertyBansSource;
import net.exylia.proxyutils.punish.LiteBansSource;
import net.exylia.proxyutils.punish.Punishments;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * The BungeeCord half: reads the config, opens the Redis bridge, hooks the ban plugins it finds,
 * turns away players ExyliaStaff has banned, and closes it all.
 */
public final class ExyliaProxyUtilsBungee extends Plugin implements Listener {

    private RedisBridge bridge;

    @Override
    public void onEnable() {
        try {
            BridgeConfig config = BridgeConfig.load(getDataFolder().toPath());
            BungeePlatform platform = new BungeePlatform(getProxy(), getLogger(), getDescription().getVersion());
            bridge = RedisBridge.start(platform, config);
            Punishments punishments = bridge.punishments();
            punishments.install(LiteBansSource.ORIGIN, () -> LiteBansSource.install(platform, punishments.stream()));
            punishments.install(LibertyBansSource.ORIGIN,
                    () -> LibertyBansSource.install(platform, punishments.stream()));
            punishments.install(AdvancedBanSource.ORIGIN,
                    () -> AdvancedBanSource.install(this, platform, punishments.stream()));
            punishments.install(BanManagerSource.ORIGIN, () -> BanManagerBungee.install(this, punishments.stream()));
            getProxy().getPluginManager().registerListener(this, this);
            getLogger().info("ExyliaProxyUtils " + getDescription().getVersion() + " ready on Redis "
                    + config.host() + ":" + config.port() + "; modules: " + bridge.modules());
        } catch (Exception failure) {
            getLogger().severe("ExyliaProxyUtils could not start; check "
                    + getDataFolder() + "/config.properties: " + failure.getMessage());
        }
    }

    /** Off the network thread: the mirror is a Redis round trip. */
    @EventHandler(priority = EventPriority.LOW)
    public void onLogin(LoginEvent event) {
        if (event.isCancelled() || bridge == null) {
            return;
        }
        SocketAddress socket = event.getConnection().getSocketAddress();
        String address = socket instanceof InetSocketAddress inet && inet.getAddress() != null
                ? inet.getAddress().getHostAddress() : null;
        event.registerIntent(this);
        getProxy().getScheduler().runAsync(this, () -> {
            try {
                bridge.mirror().check(event.getConnection().getUniqueId(), address).ifPresent(denial -> {
                    event.setCancelReason(TextComponent.fromLegacy(
                            denial.legacy().isBlank() ? "You are banned from this network." : denial.legacy()));
                    event.setCancelled(true);
                });
            } finally {
                event.completeIntent(this);
            }
        });
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.close();
        }
    }
}
