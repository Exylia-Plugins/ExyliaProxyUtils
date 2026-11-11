package net.exylia.proxyutils.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the chat the proxy itself sends (announcements, other proxy plugins' messages) while a player has an ExyliaLib
 * screen open, and gives it back when the screen closes. The backend holds its own chat; this covers what never passes
 * through it.
 *
 * <p>The payload is {@code on} or {@code off}, about the request's player. Only loaded when PacketEvents is on the
 * proxy: without it the module is not registered and a backend's request is answered as unknown, which it ignores.
 */
final class ChatHoldModule implements BridgeModule, PacketListener {

    /** The most lines kept for one player; past it the oldest goes. */
    private static final int HELD_LINES = 200;

    private final Map<UUID, Held> holding = new ConcurrentHashMap<>();

    private record Held(User user, Deque<Component> lines) {
    }

    static ChatHoldModule install(Object plugin, com.velocitypowered.api.proxy.ProxyServer server) {
        ChatHoldModule module = new ChatHoldModule();
        PacketEvents.getAPI().getEventManager().registerListener(module, PacketListenerPriority.NORMAL);
        server.getEventManager().register(plugin, module);
        return module;
    }

    @Override
    public @NotNull String name() {
        return "chat-hold";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        UUID player = request.carrier();
        if (player == null) return CompletableFuture.completedFuture(Reply.rejected("no player"));
        if (request.payload().trim().equalsIgnoreCase("on")) {
            holding.putIfAbsent(player, new Held(null, new ArrayDeque<>()));
        } else {
            release(player);
        }
        return CompletableFuture.completedFuture(Reply.ok());
    }

    /** On a network thread. */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE || event.isCancelled()) return;
        UUID id = event.getUser().getUUID();
        if (id == null || !holding.containsKey(id)) return;
        WrapperPlayServerSystemChatMessage line = new WrapperPlayServerSystemChatMessage(event);
        // Action bars stay, and so do blank lines: they are the backend pushing old chat out of view.
        if (line.isOverlay() || PlainTextComponentSerializer.plainText().serialize(line.getMessage()).isBlank()) {
            return;
        }
        Held held = holding.computeIfPresent(id, (key, old) -> old.user() == null
                ? new Held(event.getUser(), old.lines()) : old);
        if (held == null) return;
        synchronized (held.lines()) {
            held.lines().addLast(line.getMessage());
            while (held.lines().size() > HELD_LINES) held.lines().removeFirst();
        }
        event.setCancelled(true);
    }

    /** Stops holding and sends what was held, in order. */
    private void release(UUID player) {
        Held held = holding.remove(player);
        if (held == null || held.user() == null) return;
        synchronized (held.lines()) {
            for (Component text : held.lines()) {
                held.user().sendPacketSilently(new WrapperPlayServerSystemChatMessage(false, text));
            }
        }
    }

    /** A screen does not survive a server switch: what it held is shown. */
    @Subscribe
    public void onSwitch(ServerConnectedEvent event) {
        release(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onQuit(DisconnectEvent event) {
        holding.remove(event.getPlayer().getUniqueId());
    }
}
