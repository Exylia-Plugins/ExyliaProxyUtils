package net.exylia.proxyutils.punish;

import litebans.api.Database;
import litebans.api.Entry;
import litebans.api.Events;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * LiteBans on the proxy, Velocity or BungeeCord: its API is the same on both. Loaded only when
 * LiteBans is there: {@link #install} checks first, and nothing that names a LiteBans class is
 * touched before that. LiteBans numbers each table on its own, so ids are {@code ban:<id>},
 * {@code mute:<id>}, {@code warn:<id>} and {@code kick:<id>}. An IP-only entry names no player and
 * is skipped.
 */
public final class LiteBansSource {

    public static final String ORIGIN = "LITEBANS";

    private static final int PAGE = 500;

    private LiteBansSource() {
    }

    /** Hooks LiteBans when it is installed; null when it is not. */
    public static @Nullable PunishSource install(@NotNull Platform platform, @NotNull PunishStream stream) {
        try {
            Class.forName("litebans.api.Events");
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        return Hooked.hook(platform, stream);
    }

    /** Everything that needs LiteBans; its class is only loaded after the check above. */
    private static final class Hooked extends Events.Listener implements PunishSource {

        private final Platform platform;
        private final PunishStream stream;

        private Hooked(Platform platform, PunishStream stream) {
            this.platform = platform;
            this.stream = stream;
        }

        /** Returns the interface, so verifying {@link LiteBansSource} never loads this class. */
        static PunishSource hook(Platform platform, PunishStream stream) {
            Hooked hooked = new Hooked(platform, stream);
            Events.get().register(hooked);
            return hooked;
        }

        @Override
        public @NotNull String origin() {
            return ORIGIN;
        }

        @Override
        public void close() {
            Events.get().unregister(this);
        }

        @Override
        public void entryAdded(Entry entry) {
            ExternalPunishment added = of(entry);
            if (added != null) {
                stream.add(added);
            }
        }

        @Override
        public void entryRemoved(Entry entry) {
            ExternalPunishment added = of(entry);
            if (added != null) {
                stream.add(added.removed(System.currentTimeMillis(), entry.getRemovedByName(),
                        entry.getRemovalReason()));
            }
        }

        private ExternalPunishment of(Entry entry) {
            UUID target = ExternalPunishment.uuid(entry.getUuid());
            boolean temporary = !entry.isPermanent() && entry.getDateEnd() > 0;
            String type = ExternalPunishment.type(entry.getType(), temporary, entry.isIpban());
            if (target == null || type == null) {
                return null;
            }
            return ExternalPunishment.added(ORIGIN, family(entry.getType()) + ":" + entry.getId(), type,
                    target, platform.find(target.toString()).map(ProxyPlayer::name).orElse(null),
                    ExternalPunishment.uuid(entry.getExecutorUUID()), entry.getExecutorName(),
                    entry.getReason(), entry.getServerOrigin(), entry.getDateStart(),
                    temporary ? entry.getDateEnd() : 0);
        }

        /** Straight from the tables, a page at a time by id, so the history never sits in memory. */
        @Override
        public void importAll(@NotNull Consumer<ExternalPunishment> sink) throws SQLException {
            table("{bans}", "ban", sink);
            table("{mutes}", "mute", sink);
            table("{warnings}", "warn", sink);
            table("{kicks}", "kick", sink);
        }

        private void table(String table, String family, Consumer<ExternalPunishment> sink) throws SQLException {
            long after = 0;
            long now = System.currentTimeMillis();
            while (true) {
                int read = 0;
                try (PreparedStatement statement = Database.get().prepareStatement(
                        "SELECT * FROM " + table + " WHERE id > ? ORDER BY id LIMIT " + PAGE)) {
                    statement.setLong(1, after);
                    try (ResultSet rows = statement.executeQuery()) {
                        Set<String> columns = columns(rows.getMetaData());
                        while (rows.next()) {
                            read++;
                            after = rows.getLong("id");
                            row(rows, columns, family, after, now, sink);
                        }
                    }
                }
                if (read < PAGE) {
                    return;
                }
            }
        }

        private static void row(ResultSet rows, Set<String> columns, String family, long id, long now,
                                Consumer<ExternalPunishment> sink) throws SQLException {
            UUID target = ExternalPunishment.uuid(text(rows, columns, "uuid"));
            long until = number(rows, columns, "until");
            boolean temporary = until > 0;
            String type = ExternalPunishment.type(family, temporary, bool(rows, columns, "ipban"));
            if (target == null || type == null) {
                return;
            }
            ExternalPunishment added = ExternalPunishment.added(ORIGIN, family + ":" + id, type, target, null,
                    ExternalPunishment.uuid(text(rows, columns, "banned_by_uuid")),
                    text(rows, columns, "banned_by_name"), text(rows, columns, "reason"),
                    text(rows, columns, "server_origin"), number(rows, columns, "time"), temporary ? until : 0);
            sink.accept(added);
            if (family.equals("kick")) {
                return;
            }
            boolean active = !columns.contains("active") || rows.getBoolean("active");
            if (active && !(temporary && until <= now)) {
                return;
            }
            Timestamp removed = columns.contains("removed_by_date") ? rows.getTimestamp("removed_by_date") : null;
            long at = removed != null ? removed.getTime() : temporary && until <= now ? until : now;
            sink.accept(added.removed(at, text(rows, columns, "removed_by_name"),
                    text(rows, columns, "removed_by_reason")));
        }
    }

    /** LiteBans writes {@code warn} for a warning; ids say {@code warn:} too. */
    static @NotNull String family(@NotNull String type) {
        return type.toLowerCase(Locale.ROOT);
    }

    private static Set<String> columns(ResultSetMetaData meta) throws SQLException {
        Set<String> names = new HashSet<>();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            names.add(meta.getColumnLabel(column).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String text(ResultSet rows, Set<String> columns, String name) throws SQLException {
        return columns.contains(name) ? rows.getString(name) : null;
    }

    private static long number(ResultSet rows, Set<String> columns, String name) throws SQLException {
        return columns.contains(name) ? rows.getLong(name) : 0;
    }

    private static boolean bool(ResultSet rows, Set<String> columns, String name) throws SQLException {
        return columns.contains(name) && rows.getBoolean(name);
    }
}
