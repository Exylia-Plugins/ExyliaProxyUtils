package net.exylia.proxyutils;

import net.exylia.proxyutils.module.PunishImportModule;
import net.exylia.proxyutils.module.PunishSourcesModule;
import net.exylia.proxyutils.punish.BanMirror;
import net.exylia.proxyutils.punish.PunishStream;
import net.exylia.proxyutils.punish.Punishments;
import net.exylia.proxyutils.wire.Wire;
import net.exylia.proxyutils.wire.Wires;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The bridge to the backends: one channel in, one channel per backend out.
 *
 * <p>Requests arrive on {@code <prefix>:bridge:proxy}; answers and pushes go
 * to {@code <prefix>:bridge:<server-id>}. Nothing a player sends can reach
 * this: the {@link Wire} (Redis, or the backends' database with
 * {@code transport=database}) is on the network's own side, and a backend is
 * the only thing that can publish there. That is the reason this is not
 * plugin messages, which a modified client can forge.
 *
 * <p>The same wire also carries the punishment side: {@link #punishments()} writes what the ban
 * plugins on this proxy do to {@code <prefix>:punish:external}, and {@link #mirror()} reads
 * ExyliaStaff's bans at login.
 */
public final class RedisBridge implements Publisher, AutoCloseable {

    private final Platform platform;
    private final Bridge bridge;
    private final String prefix;
    private final Wire wire;
    private final Punishments punishments;
    private final BanMirror mirror;

    private RedisBridge(Platform platform, BridgeConfig settings, Wire wire) {
        this.platform = platform;
        this.prefix = settings.keyPrefix();
        this.wire = wire;
        this.bridge = Bridge.withBuiltIns(platform, this);
        this.punishments = new Punishments(platform, new PunishStream(platform, wire, prefix));
        this.mirror = new BanMirror(platform, wire, prefix);
        bridge.register(new PunishSourcesModule(punishments));
        bridge.register(new PunishImportModule(punishments));
    }

    /**
     * Connects, checks the wire answers, and starts listening.
     *
     * @throws RuntimeException if it cannot be reached, so the proxy's log
     *                          says so at startup rather than never
     */
    public static @NotNull RedisBridge start(@NotNull Platform platform, @NotNull BridgeConfig settings) {
        Wire wire = Wires.open(platform, settings);
        RedisBridge started;
        try {
            started = new RedisBridge(platform, settings, wire);
            wire.listen(Frames.channelOf(settings.keyPrefix(), Frames.PROXY), message -> {
                try {
                    started.receive(message);
                } catch (Throwable failure) {
                    // One bad request must not end the subscription.
                    platform.warn("A request failed: " + failure);
                }
            });
        } catch (RuntimeException failure) {
            wire.close();
            throw failure;
        }
        return started;
    }

    /** The modules servers can address. */
    public @NotNull java.util.Set<String> modules() {
        return bridge.modules();
    }

    /** The ban plugins hooked on this proxy, and the stream they write to. */
    public @NotNull Punishments punishments() {
        return punishments;
    }

    /** ExyliaStaff's bans, for the login check. */
    public @NotNull BanMirror mirror() {
        return mirror;
    }

    /** Adds a module; see {@link Bridge#register}. */
    public void register(@NotNull BridgeModule module) {
        bridge.register(module);
    }

    @Override
    public void push(@NotNull String server, @NotNull String module, @Nullable UUID carrier,
                     @NotNull String payload) {
        publish(server, Frames.push(module, carrier, payload));
    }

    private void publish(String server, String message) {
        try {
            wire.publish(Frames.channelOf(prefix, server), message);
        } catch (RuntimeException unreachable) {
            platform.warn("Could not answer " + server + ": " + unreachable.getMessage());
        }
    }

    private void receive(String raw) {
        Frames.Incoming incoming;
        try {
            incoming = Frames.decode(raw);
        } catch (RuntimeException unreadable) {
            platform.warn("Dropped an unreadable request: " + unreadable.getMessage());
            return;
        }
        String carrierName = incoming.carrier() == null ? ""
                : platform.find(incoming.carrier().toString()).map(ProxyPlayer::name).orElse("");
        Request request = new Request(incoming.module(), incoming.payload(), incoming.carrier(),
                carrierName, incoming.server());
        bridge.receive(request, reply -> publish(incoming.server(),
                Frames.encode(incoming.module(), incoming.id(), incoming.carrier(), reply)));
    }

    @Override
    public void close() {
        punishments.close();
        punishments.stream().close();
        wire.close();
    }
}
