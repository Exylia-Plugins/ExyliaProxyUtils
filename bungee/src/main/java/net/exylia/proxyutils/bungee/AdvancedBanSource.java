package net.exylia.proxyutils.bungee;

import me.leoko.advancedban.bungee.event.PunishmentEvent;
import me.leoko.advancedban.bungee.event.RevokePunishmentEvent;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.SQLQuery;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import net.exylia.proxyutils.punish.ExternalPunishment;
import net.exylia.proxyutils.punish.PunishSource;
import net.exylia.proxyutils.punish.PunishStream;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AdvancedBan on BungeeCord (it has no Velocity build). Loaded only when AdvancedBan is there, like
 * the common sources.
 *
 * <p>Ids are AdvancedBan's {@code Punishments} ids. A kick never gets one (AdvancedBan writes kicks
 * to history only), so a live kick is {@code kick:<uuid>:<start>}. History has its own numbering: an
 * import writes a punishment that is only in history as {@code history:<id>}. An IP punishment
 * keeps the address where the uuid goes, names no player, and is skipped.
 */
public final class AdvancedBanSource {

    static final String ORIGIN = "ADVANCEDBAN";

    private AdvancedBanSource() {
    }

    /** Hooks AdvancedBan when it is installed; null when it is not. */
    static @Nullable PunishSource install(@NotNull Plugin plugin, @NotNull Platform platform,
                                          @NotNull PunishStream stream) {
        try {
            Class.forName("me.leoko.advancedban.bungee.event.PunishmentEvent");
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        return Hooked.hook(plugin, platform, stream);
    }

    public static final class Hooked implements Listener, PunishSource {

        private final Plugin plugin;
        private final Platform platform;
        private final PunishStream stream;

        private Hooked(Plugin plugin, Platform platform, PunishStream stream) {
            this.plugin = plugin;
            this.platform = platform;
            this.stream = stream;
        }

        /** Returns the interface, so verifying {@link AdvancedBanSource} never loads this class. */
        static PunishSource hook(Plugin plugin, Platform platform, PunishStream stream) {
            Hooked hooked = new Hooked(plugin, platform, stream);
            plugin.getProxy().getPluginManager().registerListener(plugin, hooked);
            return hooked;
        }

        @Override
        public @NotNull String origin() {
            return ORIGIN;
        }

        @Override
        public void close() {
            plugin.getProxy().getPluginManager().unregisterListener(this);
        }

        @EventHandler
        public void onPunish(PunishmentEvent event) {
            ExternalPunishment added = of(event.getPunishment(), null);
            if (added != null) {
                stream.add(added);
            }
        }

        /** AdvancedBan's revoke event names nobody who revoked. */
        @EventHandler
        public void onRevoke(RevokePunishmentEvent event) {
            ExternalPunishment added = of(event.getPunishment(), null);
            if (added != null) {
                stream.add(added.removed(System.currentTimeMillis(), null, null));
            }
        }

        /** Active punishments, then the history of everything that is no longer active. */
        @Override
        public void importAll(@NotNull Consumer<ExternalPunishment> sink) {
            // ponytail: AdvancedBan's API loads a whole table into a list, no paging; fine for the
            // sizes AdvancedBan servers keep, a LIMIT/OFFSET query through DatabaseManager if one is huge.
            List<Punishment> active = PunishmentManager.get().getPunishments(SQLQuery.SELECT_ALL_PUNISHMENTS);
            Set<String> current = new HashSet<>();
            for (Punishment punishment : active) {
                current.add(key(punishment));
                ExternalPunishment added = of(punishment, null);
                if (added != null) {
                    sink.accept(added);
                }
            }
            long now = System.currentTimeMillis();
            for (Punishment past : PunishmentManager.get().getPunishments(SQLQuery.SELECT_ALL_PUNISHMENTS_HISTORY)) {
                if (current.contains(key(past))) {
                    continue;
                }
                ExternalPunishment added = of(past, "history:" + past.getId());
                if (added == null) {
                    continue;
                }
                sink.accept(added);
                if (!added.type().equals("KICK")) {
                    long ended = added.expires() > 0 && added.expires() <= now ? added.expires() : now;
                    sink.accept(added.removed(ended, null, null));
                }
            }
        }

        /** What history and the active table share for the same punishment. */
        private static String key(Punishment punishment) {
            return punishment.getUuid() + "|" + punishment.getStart() + "|" + punishment.getType().name();
        }

        private ExternalPunishment of(Punishment punishment, @Nullable String id) {
            PunishmentType type = punishment.getType();
            UUID target = ExternalPunishment.uuid(punishment.getUuid());
            String kind = ExternalPunishment.type(family(type.getBasic()), type.isTemp(), type.isIpOrientated());
            if (target == null || kind == null) {
                return null;
            }
            String originId = id != null ? id : punishment.getId() > 0 ? Integer.toString(punishment.getId())
                    : "kick:" + target.toString().replace("-", "") + ":" + punishment.getStart();
            String operator = punishment.getOperator();
            UUID staff = operator == null ? null
                    : platform.find(operator).map(ProxyPlayer::id).orElse(null);
            return ExternalPunishment.added(ORIGIN, originId, kind, target, punishment.getName(), staff,
                    operator, punishment.getReason(), null, punishment.getStart(),
                    punishment.getEnd() > 0 ? punishment.getEnd() : 0);
        }

        private static String family(PunishmentType basic) {
            return switch (basic.name()) {
                case "BAN", "IP_BAN" -> "ban";
                case "WARNING" -> "warn";
                default -> basic.name().toLowerCase(Locale.ROOT);
            };
        }
    }
}
