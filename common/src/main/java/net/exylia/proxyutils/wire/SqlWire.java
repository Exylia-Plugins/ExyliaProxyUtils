package net.exylia.proxyutils.wire;

import com.google.gson.Gson;
import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The database wire on MySQL, MariaDB or PostgreSQL.
 *
 * <p>Two connections: one the poller owns, one every other call shares under
 * a lock. Traffic from a proxy is a few requests a second at most, so a pool
 * would only add a dependency. Either connection is reopened when it breaks.
 *
 * <p>The tables are ExyliaLib's {@code SqlBus} tables, created here with the
 * same statements in case the proxy starts before any backend has.
 */
final class SqlWire extends PollingWire {

    private static final Gson GSON = new Gson();
    private static final String NOTIFY_CHANNEL = "exylia_bus";

    private final Driver driver;
    private final String url;
    private final Properties credentials;
    private final boolean postgres;
    private final String describe;
    private final Object lock = new Object();
    private final Set<String> knownStreams = ConcurrentHashMap.newKeySet();
    private @Nullable Connection shared;
    private @Nullable Connection polling;
    private boolean listening;

    private SqlWire(Platform platform, Driver driver, String url, Properties credentials, boolean postgres,
                    String describe) {
        super(platform);
        this.driver = driver;
        this.url = url;
        this.credentials = credentials;
        this.postgres = postgres;
        this.describe = describe;
    }

    static @NotNull SqlWire open(@NotNull Platform platform, @NotNull DatabaseTarget target) {
        boolean postgres = target.type().equals("postgresql");
        String url = postgres
                ? "jdbc:postgresql://" + target.host() + ':' + target.portOr(5432) + '/' + target.name()
                        + "?ApplicationName=ExyliaProxyUtils"
                // The MariaDB driver speaks to MySQL 5.7 and 8 as well, so one
                // driver covers both; public-key retrieval is what MySQL 8's
                // default caching_sha2_password needs without TLS.
                : "jdbc:mariadb://" + target.host() + ':' + target.portOr(3306) + '/' + target.name()
                        + "?allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000";
        Properties credentials = new Properties();
        credentials.setProperty("user", target.username());
        credentials.setProperty("password", target.password());
        Driver driver;
        try {
            // By name, never through DriverManager: a proxy's classloaders do
            // not let DriverManager see a driver inside a plugin jar.
            driver = (Driver) Class.forName(postgres ? "org.postgresql.Driver" : "org.mariadb.jdbc.Driver")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("The " + target.type() + " driver is missing from the jar", missing);
        }
        SqlWire wire = new SqlWire(platform, driver, url, credentials, postgres,
                target.type() + " at " + target.host() + ':' + target.portOr(postgres ? 5432 : 3306) + '/'
                        + target.name());
        try {
            try {
                wire.createTables();
            } catch (SQLException raced) {
                // A backend creating them at the same moment; then they exist.
                Thread.sleep(250);
                wire.createTables();
            }
        } catch (SQLException | InterruptedException failure) {
            wire.closeStore();
            throw new IllegalStateException("Could not prepare the cross-server tables: " + failure.getMessage(),
                    failure);
        }
        return wire;
    }

    private Connection connect() throws SQLException {
        Connection connection = driver.connect(url, credentials);
        if (connection == null) {
            throw new SQLException("The driver did not accept " + url);
        }
        return connection;
    }

    /** The shared connection, reopened if it broke. Call under {@link #lock}. */
    private Connection shared() throws SQLException {
        Connection current = shared;
        if (current == null || !current.isValid(2)) {
            closeQuietly(current);
            current = connect();
            shared = current;
        }
        return current;
    }

    private Connection polling() throws SQLException {
        Connection current = polling;
        if (current == null || !current.isValid(2)) {
            closeQuietly(current);
            current = connect();
            polling = current;
            listening = false;
        }
        if (postgres && !listening) {
            try (Statement statement = current.createStatement()) {
                statement.execute("LISTEN " + NOTIFY_CHANNEL);
            }
            listening = true;
        }
        return current;
    }

    private void createTables() throws SQLException {
        boolean mysql = !postgres;
        String serial = mysql ? "BIGINT NOT NULL AUTO_INCREMENT" : "BIGINT GENERATED BY DEFAULT AS IDENTITY";
        String text = mysql ? "MEDIUMTEXT" : "TEXT";
        synchronized (lock) {
            try (Statement statement = shared().createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + MESSAGES + " (id " + serial + " PRIMARY KEY,"
                        + " channel VARCHAR(255) NOT NULL, payload " + text + " NOT NULL, created BIGINT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS " + KEYS + " (k VARCHAR(512) NOT NULL PRIMARY KEY,"
                        + " v " + text + " NOT NULL, expires BIGINT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS " + STREAMS
                        + " (stream VARCHAR(255) NOT NULL PRIMARY KEY)");
                statement.execute("CREATE TABLE IF NOT EXISTS " + ENTRIES + " (id " + serial + " PRIMARY KEY,"
                        + " stream VARCHAR(255) NOT NULL, fields " + text + " NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS " + GROUPS + " (stream VARCHAR(255) NOT NULL,"
                        + " grp VARCHAR(255) NOT NULL, last_id BIGINT NOT NULL, PRIMARY KEY (stream, grp))");
                statement.execute("CREATE TABLE IF NOT EXISTS " + PENDING + " (stream VARCHAR(255) NOT NULL,"
                        + " grp VARCHAR(255) NOT NULL, id BIGINT NOT NULL, consumer VARCHAR(255) NOT NULL,"
                        + " delivered BIGINT NOT NULL, deliveries INT NOT NULL, PRIMARY KEY (stream, grp, id))");
                index(statement, MESSAGES + "_created", MESSAGES, "created");
                index(statement, KEYS + "_expires", KEYS, "expires");
                index(statement, ENTRIES + "_stream", ENTRIES, "stream, id");
            }
        }
    }

    private void index(Statement statement, String name, String table, String columns) throws SQLException {
        if (postgres) {
            statement.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
            return;
        }
        try {
            statement.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        } catch (SQLException exists) {
            if (exists.getErrorCode() != 1061) {
                throw exists;
            }
        }
    }

    // ----------------------------------------------------------------- messages

    @Override
    long newestId() throws SQLException {
        try (Statement statement = polling().createStatement();
             ResultSet rows = statement.executeQuery("SELECT MAX(id) FROM " + MESSAGES)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    @Override
    @NotNull List<Envelope> readAfter(@NotNull String channel, long after, int limit) throws SQLException {
        List<Envelope> found = new ArrayList<>();
        try (PreparedStatement statement = polling().prepareStatement("SELECT id, payload FROM " + MESSAGES
                + " WHERE id > ? AND channel = ? ORDER BY id LIMIT ?")) {
            statement.setLong(1, after);
            statement.setString(2, channel);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new Envelope(rows.getLong(1), rows.getString(2)));
                }
            }
        }
        return found;
    }

    @Override
    void purge(long before) throws SQLException {
        try (PreparedStatement statement = polling().prepareStatement("DELETE FROM " + MESSAGES
                + " WHERE created < ?")) {
            statement.setLong(1, before);
            statement.executeUpdate();
        }
    }

    @Override
    void await(long millis) throws InterruptedException {
        if (!postgres) {
            super.await(millis);
            return;
        }
        try {
            polling().unwrap(org.postgresql.PGConnection.class).getNotifications((int) millis);
        } catch (SQLException broken) {
            closeQuietly(polling);
            polling = null;
            super.await(millis);
        }
    }

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        synchronized (lock) {
            try {
                Connection connection = shared();
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + MESSAGES
                        + " (channel, payload, created) VALUES (?, ?, ?)")) {
                    statement.setString(1, channel);
                    statement.setString(2, message);
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                if (postgres) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("NOTIFY " + NOTIFY_CHANNEL);
                    }
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
    }

    // ------------------------------------------------------------- keys, streams

    @Override
    public @Nullable String get(@NotNull String key) {
        synchronized (lock) {
            try (PreparedStatement statement = shared().prepareStatement("SELECT v, expires FROM " + KEYS
                    + " WHERE k = ?")) {
                statement.setString(1, key);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    long expires = rows.getLong(2);
                    return expires > 0 && expires <= System.currentTimeMillis() ? null : rows.getString(1);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
    }

    /**
     * Appends the way ExyliaLib does: the stream's row locked first, so a
     * group reader on a backend never moves past an append not yet committed.
     */
    @Override
    public void streamAdd(@NotNull String stream, @NotNull Map<String, String> fields, long maxLength) {
        synchronized (lock) {
            try {
                Connection connection = shared();
                if (!knownStreams.contains(stream)) {
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + STREAMS
                            + " (stream) VALUES (?)")) {
                        statement.setString(1, stream);
                        statement.executeUpdate();
                    } catch (SQLException exists) {
                        if (!duplicateKey(exists)) {
                            throw exists;
                        }
                    }
                    knownStreams.add(stream);
                }
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT stream FROM " + STREAMS
                            + " WHERE stream = ? FOR UPDATE")) {
                        statement.setString(1, stream);
                        statement.executeQuery().close();
                    }
                    long id;
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + ENTRIES
                            + " (stream, fields) VALUES (?, ?)", new String[]{"id"})) {
                        statement.setString(1, stream);
                        statement.setString(2, GSON.toJson(fields));
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            keys.next();
                            id = keys.getLong(1);
                        }
                    }
                    if (maxLength > 0) {
                        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + ENTRIES
                                + " WHERE stream = ? AND id <= ?")) {
                            statement.setString(1, stream);
                            statement.setLong(2, id - maxLength);
                            statement.executeUpdate();
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
    }

    /** 23505 on Postgres; 23000 with error 1062 on MySQL and MariaDB. */
    private static boolean duplicateKey(SQLException failure) {
        String state = failure.getSQLState();
        return "23505".equals(state) || "23000".equals(state) && failure.getErrorCode() == 1062;
    }

    @Override
    public @NotNull String describe() {
        return describe.toLowerCase(Locale.ROOT);
    }

    @Override
    void closeStore() {
        synchronized (lock) {
            closeQuietly(shared);
            shared = null;
        }
        closeQuietly(polling);
        polling = null;
    }

    private static void closeQuietly(@Nullable Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Broken already.
            }
        }
    }
}
