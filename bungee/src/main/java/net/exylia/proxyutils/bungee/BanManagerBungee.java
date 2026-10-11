package net.exylia.proxyutils.bungee;

import me.confuser.banmanager.bungee.api.events.PlayerBannedEvent;
import me.confuser.banmanager.bungee.api.events.PlayerKickedEvent;
import me.confuser.banmanager.bungee.api.events.PlayerMutedEvent;
import me.confuser.banmanager.bungee.api.events.PlayerUnbanEvent;
import me.confuser.banmanager.bungee.api.events.PlayerUnmuteEvent;
import me.confuser.banmanager.bungee.api.events.PlayerWarnedEvent;
import net.exylia.proxyutils.punish.BanManagerSource;
import net.exylia.proxyutils.punish.PunishSource;
import net.exylia.proxyutils.punish.PunishStream;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * BanManager's BungeeCord events into the stream; the mapping and the import are
 * {@link BanManagerSource}'s. Loaded only when BanManager is there.
 */
public final class BanManagerBungee {

    private BanManagerBungee() {
    }

    /** Hooks BanManager when it is installed; null when it is not. */
    static @Nullable PunishSource install(@NotNull Plugin plugin, @NotNull PunishStream stream) {
        try {
            Class.forName("me.confuser.banmanager.bungee.api.events.PlayerBannedEvent");
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        return Events.hook(plugin, stream);
    }

    /** Unban and unmute can be cancelled, so they are read last and only when they went through. */
    public static final class Events implements Listener {

        private final PunishStream stream;

        private Events(PunishStream stream) {
            this.stream = stream;
        }

        /** Returns the interface, so verifying {@link BanManagerBungee} never loads this class. */
        static PunishSource hook(Plugin plugin, PunishStream stream) {
            Events events = new Events(stream);
            plugin.getProxy().getPluginManager().registerListener(plugin, events);
            return new BanManagerSource(() -> plugin.getProxy().getPluginManager().unregisterListener(events));
        }

        @EventHandler
        public void onBan(PlayerBannedEvent event) {
            stream.add(BanManagerSource.ban(event.getBan()));
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onUnban(PlayerUnbanEvent event) {
            if (!event.isCancelled()) {
                stream.add(BanManagerSource.lifted(BanManagerSource.ban(event.getBan()), event.getActor(),
                        event.getReason()));
            }
        }

        @EventHandler
        public void onMute(PlayerMutedEvent event) {
            stream.add(BanManagerSource.mute(event.getMute()));
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onUnmute(PlayerUnmuteEvent event) {
            if (!event.isCancelled()) {
                stream.add(BanManagerSource.lifted(BanManagerSource.mute(event.getMute()), event.getActor(),
                        event.getReason()));
            }
        }

        @EventHandler
        public void onWarn(PlayerWarnedEvent event) {
            stream.add(BanManagerSource.warn(event.getWarning()));
        }

        @EventHandler
        public void onKick(PlayerKickedEvent event) {
            stream.add(BanManagerSource.kick(event.getKick()));
        }
    }
}
