package net.exylia.proxyutils.bungee;

import net.exylia.proxyutils.BridgeConfig;
import net.exylia.proxyutils.RedisBridge;
import net.md_5.bungee.api.plugin.Plugin;

/** The BungeeCord half: reads the config, opens the Redis bridge, closes it. */
public final class ExyliaProxyUtilsBungee extends Plugin {

    private RedisBridge bridge;

    @Override
    public void onEnable() {
        try {
            BridgeConfig config = BridgeConfig.load(getDataFolder().toPath());
            bridge = RedisBridge.start(new BungeePlatform(getProxy(), getLogger(),
                    getDescription().getVersion()), config);
            getLogger().info("ExyliaProxyUtils " + getDescription().getVersion() + " ready on Redis "
                    + config.host() + ":" + config.port() + "; modules: " + bridge.modules());
        } catch (Exception failure) {
            getLogger().severe("ExyliaProxyUtils could not start; check "
                    + getDataFolder() + "/config.properties: " + failure.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.close();
        }
    }
}
