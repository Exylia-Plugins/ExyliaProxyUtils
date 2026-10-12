package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where the Redis is, read from {@code config.properties} in the plugin's folder.
 *
 * <p>The same values every backend has under {@code database.redis} in
 * {@code plugins/ExyliaLib/database.yml}, and the same {@code key-prefix}: two
 * networks on one Redis must not answer each other's servers.
 *
 * @param host      the Redis host
 * @param port      the port
 * @param password  the password, empty for none
 * @param database  which numbered database
 * @param keyPrefix what every key and channel of the network starts with
 * @param updates   whether new releases install themselves on restart (see {@link Updater})
 */
public record BridgeConfig(@NotNull String host, int port, @NotNull String password, int database,
                           @NotNull String keyPrefix, boolean updates) {

    private static final String FILE = "config.properties";

    /** Reads the file, writing the defaults first if it does not exist. */
    public static @NotNull BridgeConfig load(@NotNull Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve(FILE);
        if (Files.notExists(file)) {
            Files.writeString(file, """
                    # ExyliaProxyUtils talks to the backends over Redis, so nothing a player
                    # sends can ever reach it. Point this at the same Redis every backend has
                    # under database.redis in plugins/ExyliaLib/database.yml, with the same
                    # key-prefix. Server names on this proxy must equal the backends' server-id.
                    redis.host=localhost
                    redis.port=6379
                    redis.password=
                    redis.database=0
                    redis.key-prefix=exylia
                    # New releases download at start and stop and load on the next restart.
                    updates.enabled=true
                    """);
        }
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        }
        String prefix = values.getProperty("redis.key-prefix", "exylia").trim();
        return new BridgeConfig(
                values.getProperty("redis.host", "localhost").trim(),
                Integer.parseInt(values.getProperty("redis.port", "6379").trim()),
                values.getProperty("redis.password", ""),
                Integer.parseInt(values.getProperty("redis.database", "0").trim()),
                prefix.isEmpty() ? "exylia" : prefix,
                !values.getProperty("updates.enabled", "true").trim().equalsIgnoreCase("false"));
    }
}
