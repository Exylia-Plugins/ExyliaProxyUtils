package net.exylia.proxyutils.wire;

import net.exylia.proxyutils.BridgeConfig;
import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;

/** Opens the wire {@code config.properties} asks for. */
public final class Wires {

    private Wires() {
    }

    /**
     * Opens Redis, or the backends' database with {@code transport=database}.
     *
     * @throws RuntimeException if it cannot be reached, so the proxy's log says so at startup
     */
    public static @NotNull Wire open(@NotNull Platform platform, @NotNull BridgeConfig config) {
        if (!config.byDatabase()) {
            return RedisWire.open(platform, config);
        }
        DatabaseTarget target = config.target();
        if (!target.supported()) {
            throw new IllegalStateException("transport=database needs database.type mysql, mariadb, postgresql"
                    + " or mongodb, not \"" + target.type() + "\"");
        }
        Wire wire = target.type().equals("mongodb")
                ? MongoWire.open(platform, target)
                : SqlWire.open(platform, target);
        platform.info("Talking to the backends through " + wire.describe() + " (transport=database)."
                + " It works, but each message takes up to a quarter of a second; Redis is the recommended setup.");
        return wire;
    }
}
