package net.exylia.proxyutils;

import net.exylia.proxyutils.wire.DatabaseTarget;
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
 * @param username  the ACL user, empty for the default user
 * @param ssl       whether to connect over TLS
 * @param database  which numbered database
 * @param keyPrefix what every key and channel of the network starts with
 * @param updates   whether new releases install themselves on restart (see {@link Updater})
 * @param byDatabase whether the backends' database carries the bridge instead of Redis
 * @param target    that database, read only when {@code byDatabase}
 */
public record BridgeConfig(@NotNull String host, int port, @NotNull String password,
                           @NotNull String username, boolean ssl, int database,
                           @NotNull String keyPrefix, boolean updates,
                           boolean byDatabase, @NotNull DatabaseTarget target) {

    private static final String FILE = "config.properties";

    /** The section a file written before {@code transport} existed is given, so its owner can find it. */
    private static final String TRANSPORT_SECTION = """

            # How this proxy reaches the backends: redis (above, recommended), or
            # database for a network whose backends set cross-server: database in
            # their database.yml. Then fill in the same database they use; the
            # redis.key-prefix above still names the network.
            transport=redis
            # mysql, mariadb, postgresql or mongodb
            database.type=mysql
            database.host=localhost
            # 0 uses the engine's default port.
            database.port=0
            database.name=minecraft
            database.username=root
            database.password=
            # mongodb only: a full mongodb:// URI, which wins over the fields above.
            database.connection-string=
            """;

    /** Reads the file, writing the defaults first if it does not exist. */
    public static @NotNull BridgeConfig load(@NotNull Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve(FILE);
        if (Files.notExists(file)) {
            Files.writeString(file, """
                    # ExyliaProxyUtils talks to the backends over Redis (or their database,
                    # see transport below), so nothing a player
                    # sends can ever reach it. Point this at the same Redis every backend has
                    # under database.redis in plugins/ExyliaLib/database.yml, with the same
                    # key-prefix. Server names on this proxy must equal the backends' server-id.
                    redis.host=localhost
                    redis.port=6379
                    redis.password=
                    # ACL user (Redis 6+), empty for the default user.
                    redis.username=
                    # true for a Redis with TLS on (rediss://), like the backends' database.redis.ssl.
                    redis.ssl=false
                    redis.database=0
                    redis.key-prefix=exylia
                    # New releases download at start and stop and load on the next restart.
                    updates.enabled=true
                    """ + TRANSPORT_SECTION.stripLeading());
        }
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        }
        if (!values.containsKey("transport")) {
            // Appended, never rewritten: the owner's own lines and comments
            // stay as they are, and transport=redis is what the file meant.
            Files.writeString(file, TRANSPORT_SECTION, java.nio.file.StandardOpenOption.APPEND);
        }
        String prefix = values.getProperty("redis.key-prefix", "exylia").trim();
        return new BridgeConfig(
                values.getProperty("redis.host", "localhost").trim(),
                Integer.parseInt(values.getProperty("redis.port", "6379").trim()),
                values.getProperty("redis.password", ""),
                values.getProperty("redis.username", "").trim(),
                values.getProperty("redis.ssl", "false").trim().equalsIgnoreCase("true"),
                Integer.parseInt(values.getProperty("redis.database", "0").trim()),
                prefix.isEmpty() ? "exylia" : prefix,
                !values.getProperty("updates.enabled", "true").trim().equalsIgnoreCase("false"),
                values.getProperty("transport", "redis").trim().equalsIgnoreCase("database"),
                new DatabaseTarget(
                        values.getProperty("database.type", "mysql"),
                        values.getProperty("database.host", "localhost").trim(),
                        port(values.getProperty("database.port", "0")),
                        values.getProperty("database.name", "minecraft").trim(),
                        values.getProperty("database.username", "root").trim(),
                        values.getProperty("database.password", ""),
                        values.getProperty("database.connection-string", "").trim()));
    }

    private static int port(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException unset) {
            return 0;
        }
    }
}
