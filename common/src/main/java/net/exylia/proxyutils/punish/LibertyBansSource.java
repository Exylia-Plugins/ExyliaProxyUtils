package net.exylia.proxyutils.punish;

import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.ProxyPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import space.arim.libertybans.api.AddressVictim;
import space.arim.libertybans.api.CompositeVictim;
import space.arim.libertybans.api.ConsoleOperator;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.Operator;
import space.arim.libertybans.api.PlayerOperator;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.Victim;
import space.arim.libertybans.api.event.PostPardonEvent;
import space.arim.libertybans.api.event.PostPunishEvent;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.libertybans.api.select.SelectionOrderBuilder;
import space.arim.libertybans.api.select.SortPunishments;
import space.arim.omnibus.OmnibusProvider;
import space.arim.omnibus.events.EventBus;
import space.arim.omnibus.events.EventConsumer;
import space.arim.omnibus.events.ListenerPriorities;
import space.arim.omnibus.events.RegisteredListener;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * LibertyBans on the proxy, Velocity or BungeeCord, through its event bus Omnibus. Loaded only when
 * LibertyBans is there, like {@link LiteBansSource}. Ids are LibertyBans' own, one sequence for every
 * type. An address-only punishment names no player and is skipped.
 */
public final class LibertyBansSource {

    public static final String ORIGIN = "LIBERTYBANS";

    private static final int PAGE = 500;
    private static final long PAGE_TIMEOUT_SECONDS = 60;

    private LibertyBansSource() {
    }

    /** Hooks LibertyBans when it is installed; null when it is not. */
    public static @Nullable PunishSource install(@NotNull Platform platform, @NotNull PunishStream stream) {
        try {
            Class.forName("space.arim.libertybans.api.event.PostPunishEvent");
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        return Hooked.hook(platform, stream);
    }

    private static final class Hooked implements PunishSource {

        private final Platform platform;
        private final List<RegisteredListener> listeners;

        private Hooked(Platform platform, PunishStream stream) {
            this.platform = platform;
            EventBus bus = OmnibusProvider.getOmnibus().getEventBus();
            this.listeners = List.of(
                    bus.registerListener(PostPunishEvent.class, ListenerPriorities.NORMAL,
                            (EventConsumer<PostPunishEvent>) event -> {
                                ExternalPunishment added = of(event.getPunishment());
                                if (added != null) {
                                    stream.add(added);
                                }
                            }),
                    bus.registerListener(PostPardonEvent.class, ListenerPriorities.NORMAL,
                            (EventConsumer<PostPardonEvent>) event -> {
                                ExternalPunishment added = of(event.getPunishment());
                                if (added != null) {
                                    stream.add(added.removed(System.currentTimeMillis(),
                                            name(event.getOperator()), null));
                                }
                            }));
        }

        /** Returns the interface, so verifying {@link LibertyBansSource} never loads this class. */
        static PunishSource hook(Platform platform, PunishStream stream) {
            return new Hooked(platform, stream);
        }

        @Override
        public @NotNull String origin() {
            return ORIGIN;
        }

        @Override
        public void close() {
            EventBus bus = OmnibusProvider.getOmnibus().getEventBus();
            listeners.forEach(bus::unregisterListener);
        }

        /**
         * Every punishment, oldest first, a page at a time. LibertyBans keeps no date for a pardon,
         * so a lifted one is {@code removed} at its end date when it ran out, else at the import.
         */
        @Override
        public void importAll(@NotNull Consumer<ExternalPunishment> sink) throws Exception {
            LibertyBans api = OmnibusProvider.getOmnibus().getRegistry().getProvider(LibertyBans.class)
                    .orElseThrow(() -> new IllegalStateException("LibertyBans has not started"));
            Set<Long> active = new HashSet<>();
            page(api, true, punishment -> active.add(punishment.getIdentifier()));
            long now = System.currentTimeMillis();
            page(api, false, punishment -> {
                ExternalPunishment added = of(punishment);
                if (added == null) {
                    return;
                }
                sink.accept(added);
                if (punishment.getType() != PunishmentType.KICK && !active.contains(punishment.getIdentifier())) {
                    long ended = added.expires() > 0 && added.expires() <= now ? added.expires() : now;
                    sink.accept(added.removed(ended, null, null));
                }
            });
        }

        private static void page(LibertyBans api, boolean activeOnly, Consumer<Punishment> each)
                throws Exception {
            for (int skip = 0; ; skip += PAGE) {
                SelectionOrderBuilder builder = api.getSelector().selectionBuilder()
                        .selectActiveOnly(activeOnly).skipFirstRetrieved(skip).limitToRetrieve(PAGE);
                List<Punishment> page = builder.build().getAllSpecificPunishments(SortPunishments.OLDEST_FIRST)
                        .toCompletableFuture().get(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                page.forEach(each);
                if (page.size() < PAGE) {
                    return;
                }
            }
        }

        private ExternalPunishment of(Punishment punishment) {
            Victim victim = punishment.getVictim();
            UUID target = victim instanceof PlayerVictim player ? player.getUUID()
                    : victim instanceof CompositeVictim composite ? composite.getUUID() : null;
            boolean ip = victim instanceof CompositeVictim || victim instanceof AddressVictim;
            String type = ExternalPunishment.type(punishment.getType().name().toLowerCase(Locale.ROOT),
                    !punishment.isPermanent(), ip);
            if (target == null || target.equals(CompositeVictim.WILDCARD_UUID) || type == null) {
                return null;
            }
            Operator operator = punishment.getOperator();
            return ExternalPunishment.added(ORIGIN, Long.toString(punishment.getIdentifier()), type, target,
                    online(target), operator instanceof PlayerOperator player ? player.getUUID() : null,
                    name(operator), punishment.getReason(), null,
                    punishment.getStartDate().toEpochMilli(),
                    punishment.isPermanent() ? 0 : punishment.getEndDate().toEpochMilli());
        }

        /** LibertyBans keeps no operator names: the console by this word, a player by whoever is online. */
        private String name(Operator operator) {
            if (operator instanceof ConsoleOperator) {
                return "Console";
            }
            return operator instanceof PlayerOperator player ? online(player.getUUID()) : null;
        }

        private String online(UUID player) {
            return platform.find(player.toString()).map(ProxyPlayer::name).orElse(null);
        }
    }
}
