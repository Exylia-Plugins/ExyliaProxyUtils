package net.exylia.proxyutils.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.ProxyServer;
import me.confuser.banmanager.velocity.api.events.PlayerBannedEvent;
import me.confuser.banmanager.velocity.api.events.PlayerKickedEvent;
import me.confuser.banmanager.velocity.api.events.PlayerMutedEvent;
import me.confuser.banmanager.velocity.api.events.PlayerUnbanEvent;
import me.confuser.banmanager.velocity.api.events.PlayerUnmuteEvent;
import me.confuser.banmanager.velocity.api.events.PlayerWarnedEvent;
import net.exylia.proxyutils.punish.BanManagerSource;
import net.exylia.proxyutils.punish.PunishSource;
import net.exylia.proxyutils.punish.PunishStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * BanManager's Velocity events into the stream; the mapping and the import are
 * {@link BanManagerSource}'s. Loaded only when BanManager is there.
 */
public final class BanManagerVelocity {

    private BanManagerVelocity() {
    }

    /** Hooks BanManager when it is installed; null when it is not. */
    static @Nullable PunishSource install(@NotNull Object plugin, @NotNull ProxyServer server,
                                          @NotNull PunishStream stream) {
        try {
            Class.forName("me.confuser.banmanager.velocity.api.events.PlayerBannedEvent");
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        return Events.hook(plugin, server, stream);
    }

    /** Unban and unmute can be cancelled, so they are read last and only when they went through. */
    public static final class Events {

        private final PunishStream stream;

        private Events(PunishStream stream) {
            this.stream = stream;
        }

        /** Returns the interface, so verifying {@link BanManagerVelocity} never loads this class. */
        static PunishSource hook(Object plugin, ProxyServer server, PunishStream stream) {
            Events events = new Events(stream);
            server.getEventManager().register(plugin, events);
            return new BanManagerSource(() -> server.getEventManager().unregisterListener(plugin, events));
        }

        @Subscribe
        public void onBan(PlayerBannedEvent event) {
            stream.add(BanManagerSource.ban(event.getBan()));
        }

        @Subscribe(order = PostOrder.LAST)
        public void onUnban(PlayerUnbanEvent event) {
            if (event.getResult().isAllowed()) {
                stream.add(BanManagerSource.lifted(BanManagerSource.ban(event.getBan()), event.getActor(),
                        event.getReason()));
            }
        }

        @Subscribe
        public void onMute(PlayerMutedEvent event) {
            stream.add(BanManagerSource.mute(event.getMute()));
        }

        @Subscribe(order = PostOrder.LAST)
        public void onUnmute(PlayerUnmuteEvent event) {
            if (event.getResult().isAllowed()) {
                stream.add(BanManagerSource.lifted(BanManagerSource.mute(event.getMute()), event.getActor(),
                        event.getReason()));
            }
        }

        @Subscribe
        public void onWarn(PlayerWarnedEvent event) {
            stream.add(BanManagerSource.warn(event.getWarning()));
        }

        @Subscribe
        public void onKick(PlayerKickedEvent event) {
            stream.add(BanManagerSource.kick(event.getKick()));
        }
    }
}
