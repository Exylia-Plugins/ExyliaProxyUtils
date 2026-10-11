package net.exylia.proxyutils.punish;

import me.confuser.banmanager.common.BanManagerPlugin;
import me.confuser.banmanager.common.data.PlayerBanData;
import me.confuser.banmanager.common.data.PlayerBanRecord;
import me.confuser.banmanager.common.data.PlayerData;
import me.confuser.banmanager.common.data.PlayerKickData;
import me.confuser.banmanager.common.data.PlayerMuteData;
import me.confuser.banmanager.common.data.PlayerMuteRecord;
import me.confuser.banmanager.common.data.PlayerWarnData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.function.Consumer;

/**
 * BanManager's punishments, the part both proxies share: what its data means, and its history.
 * Its events are per platform, so each proxy's main listens and calls the mappers here; this class
 * is only loaded from those listeners, after the platform checked BanManager is installed.
 *
 * <p>BanManager stores seconds. Active rows and their history are separate tables with their own
 * ids, so a lifted ban that is only in history is {@code banrecord:<id>} ({@code muterecord:<id>}
 * for mutes), never confused with {@code ban:<id>}. Its IP bans name no player and are skipped.
 */
public final class BanManagerSource implements PunishSource {

    public static final String ORIGIN = "BANMANAGER";

    private final Runnable unregister;

    /** @param unregister stops the platform's listener */
    public BanManagerSource(@NotNull Runnable unregister) {
        this.unregister = unregister;
    }

    @Override
    public @NotNull String origin() {
        return ORIGIN;
    }

    @Override
    public void close() {
        unregister.run();
    }

    public static @NotNull ExternalPunishment ban(@NotNull PlayerBanData ban) {
        return of("ban:" + ban.getId(), "ban", ban.getPlayer(), ban.getActor(), ban.getReason(),
                ban.getCreated(), ban.getExpires());
    }

    public static @NotNull ExternalPunishment mute(@NotNull PlayerMuteData mute) {
        return of("mute:" + mute.getId(), "mute", mute.getPlayer(), mute.getActor(), mute.getReason(),
                mute.getCreated(), mute.getExpires());
    }

    public static @NotNull ExternalPunishment warn(@NotNull PlayerWarnData warn) {
        return of("warn:" + warn.getId(), "warn", warn.getPlayer(), warn.getActor(), warn.getReason(),
                warn.getCreated(), warn.getExpires());
    }

    public static @NotNull ExternalPunishment kick(@NotNull PlayerKickData kick) {
        return of("kick:" + kick.getId(), "kick", kick.getPlayer(), kick.getActor(), kick.getReason(),
                kick.getCreated(), 0);
    }

    /** A ban or a mute that was just lifted. */
    public static @NotNull ExternalPunishment lifted(@NotNull ExternalPunishment added, @Nullable PlayerData actor,
                                                     @Nullable String reason) {
        return added.removed(System.currentTimeMillis(), actor == null ? null : actor.getName(), reason);
    }

    /**
     * Every table BanManager keeps players' punishments in, a row at a time through its own DAOs'
     * cursors, which the plugin keeps open over the database it already has.
     */
    @Override
    public void importAll(@NotNull Consumer<ExternalPunishment> sink) throws Exception {
        BanManagerPlugin plugin = BanManagerPlugin.getInstance();
        each(plugin, "getPlayerBanStorage", PlayerBanData.class, row -> sink.accept(ban(row)));
        each(plugin, "getPlayerBanRecordStorage", PlayerBanRecord.class, row -> {
            ExternalPunishment added = of("banrecord:" + row.getId(), "ban", row.getPlayer(), row.getPastActor(),
                    row.getReason(), row.getPastCreated(), row.getExpired());
            sink.accept(added);
            sink.accept(added.removed(row.getCreated() * 1000, name(row.getActor()), row.getCreatedReason()));
        });
        each(plugin, "getPlayerMuteStorage", PlayerMuteData.class, row -> sink.accept(mute(row)));
        each(plugin, "getPlayerMuteRecordStorage", PlayerMuteRecord.class, row -> {
            ExternalPunishment added = of("muterecord:" + row.getId(), "mute", row.getPlayer(),
                    row.getPastActor(), row.getReason(), row.getPastCreated(), row.getExpired());
            sink.accept(added);
            sink.accept(added.removed(row.getCreated() * 1000, name(row.getActor()), row.getCreatedReason()));
        });
        each(plugin, "getPlayerWarnStorage", PlayerWarnData.class, row -> sink.accept(warn(row)));
        each(plugin, "getPlayerKickStorage", PlayerKickData.class, row -> sink.accept(kick(row)));
    }

    /**
     * Walks one storage. The storages are ORMLite DAOs, which are {@link Iterable} over a database
     * cursor; they are reached by name because their ORMLite superclass is shaded inside BanManager's
     * jar and not on this plugin's compile path.
     */
    private static <T> void each(BanManagerPlugin plugin, String storage, Class<T> type, Consumer<T> row)
            throws Exception {
        Iterable<?> rows = (Iterable<?>) plugin.getClass().getMethod(storage).invoke(plugin);
        Iterator<?> cursor = rows.iterator();
        try {
            while (cursor.hasNext()) {
                row.accept(type.cast(cursor.next()));
            }
        } finally {
            if (cursor instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static ExternalPunishment of(String id, String family, PlayerData player, PlayerData actor,
                                         String reason, long createdSeconds, long expiresSeconds) {
        boolean console = actor == null || "console".equalsIgnoreCase(actor.getName());
        return ExternalPunishment.added(ORIGIN, id, ExternalPunishment.type(family, expiresSeconds > 0, false),
                player.getUUID(), player.getName(), console ? null : actor.getUUID(), name(actor), reason, null,
                createdSeconds * 1000, expiresSeconds > 0 ? expiresSeconds * 1000 : 0);
    }

    private static String name(PlayerData actor) {
        return actor == null ? null : actor.getName();
    }
}
