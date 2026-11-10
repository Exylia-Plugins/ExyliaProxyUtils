package net.exylia.proxyutils.wire;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The backends' database, as the proxy reaches it with {@code transport=database}.
 *
 * @param type             mysql, mariadb, postgresql or mongodb
 * @param host             the host
 * @param port             the port, {@code 0} for the engine's default
 * @param name             the database name
 * @param username         the user
 * @param password         the password
 * @param connectionString a full MongoDB URI, which wins over the fields; empty for none
 */
public record DatabaseTarget(@NotNull String type, @NotNull String host, int port, @NotNull String name,
                             @NotNull String username, @NotNull String password,
                             @NotNull String connectionString) {

    public DatabaseTarget {
        String engine = type.toLowerCase(Locale.ROOT).trim();
        type = switch (engine) {
            case "postgres", "pgsql" -> "postgresql";
            case "mongo" -> "mongodb";
            default -> engine;
        };
    }

    int portOr(int fallback) {
        return port > 0 ? port : fallback;
    }

    /** Whether the type is one the wire can use. */
    public boolean supported() {
        return switch (type) {
            case "mysql", "mariadb", "postgresql", "mongodb" -> true;
            default -> false;
        };
    }
}
