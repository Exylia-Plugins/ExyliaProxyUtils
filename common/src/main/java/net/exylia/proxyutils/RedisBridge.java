package net.exylia.proxyutils;

import net.exylia.proxyutils.module.PunishImportModule;
import net.exylia.proxyutils.module.PunishSourcesModule;
import net.exylia.proxyutils.punish.BanMirror;
import net.exylia.proxyutils.punish.PunishStream;
import net.exylia.proxyutils.punish.Punishments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.time.Duration;
import java.util.UUID;

/**
 * The bridge over Redis: one channel in, one channel per backend out.
 *
 * <p>Requests arrive on {@code <prefix>:bridge:proxy}; answers and pushes go
 * to {@code <prefix>:bridge:<server-id>}. Nothing a player sends can reach
 * this: Redis is on the network's own side, and a backend is the only thing
 * that can publish there. That is the reason this is Redis and not plugin
 * messages, which a modified client can forge.
 *
 * <h2>Two connection shapes, because Redis has two</h2>
 * Publishing comes from a pool. The subscription cannot: {@code SUBSCRIBE}
 * takes over its connection for as long as it listens, so it gets one of its
 * own on its own thread, with no read timeout, re-subscribing when it drops.
 *
 * <p>The same pool also carries the punishment side: {@link #punishments()} writes what the ban
 * plugins on this proxy do to {@code <prefix>:punish:external}, and {@link #mirror()} reads
 * ExyliaStaff's bans at login.
 */
public final class RedisBridge implements Publisher, AutoCloseable {

    private static final int TIMEOUT_MILLIS = 2_000;
    private static final long RECONNECT_DELAY_MILLIS = 2_000;

    private final Platform platform;
    private final Bridge bridge;
    private final String prefix;
    private final JedisPool pool;
    private final HostAndPort address;
    private final DefaultJedisClientConfig config;
    private final Thread listener;
    private final Punishments punishments;
    private final BanMirror mirror;
    private volatile boolean running = true;
    private volatile @Nullable Jedis listening;

    private RedisBridge(Platform platform, BridgeConfig settings, JedisPool pool,
                        HostAndPort address, DefaultJedisClientConfig config) {
        this.platform = platform;
        this.prefix = settings.keyPrefix();
        this.pool = pool;
        this.address = address;
        this.config = config;
        this.bridge = Bridge.withBuiltIns(platform, this);
        this.punishments = new Punishments(platform, new PunishStream(platform, pool, prefix));
        this.mirror = new BanMirror(platform, pool, prefix);
        bridge.register(new PunishSourcesModule(punishments));
        bridge.register(new PunishImportModule(punishments));
        this.listener = new Thread(this::listen, "ExyliaProxyUtils-Redis");
        this.listener.setDaemon(true);
    }

    /**
     * Connects, checks the Redis answers, and starts listening.
     *
     * @throws RuntimeException if Redis cannot be reached, so the proxy's log
     *                          says so at startup rather than never
     */
    public static @NotNull RedisBridge start(@NotNull Platform platform, @NotNull BridgeConfig settings) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(Duration.ofMillis(200));
        HostAndPort address = new HostAndPort(settings.host(), settings.port());
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(TIMEOUT_MILLIS)
                .socketTimeoutMillis(TIMEOUT_MILLIS)
                .database(settings.database())
                .clientName("exylia-proxyutils");
        if (!settings.password().isEmpty()) {
            config.password(settings.password());
        }
        DefaultJedisClientConfig built = config.build();
        JedisPool pool = new JedisPool(poolConfig, address, built);
        try (Jedis probe = pool.getResource()) {
            probe.ping();
        } catch (RuntimeException unreachable) {
            pool.close();
            throw unreachable;
        }
        RedisBridge started = new RedisBridge(platform, settings, pool, address, built);
        started.listener.start();
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
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(Frames.channelOf(prefix, server), message);
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

    private void listen() {
        String channel = Frames.channelOf(prefix, Frames.PROXY);
        JedisPubSub subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String from, String message) {
                try {
                    receive(message);
                } catch (Throwable failure) {
                    // One bad request must not end the subscription.
                    platform.warn("A request failed: " + failure);
                }
            }
        };
        while (running) {
            try (Jedis jedis = new Jedis(address, config)) {
                listening = jedis;
                jedis.subscribe(subscriber, channel);
            } catch (Throwable dropped) {
                if (running) {
                    platform.warn("Redis subscription dropped (" + dropped.getMessage()
                            + "); retrying in " + RECONNECT_DELAY_MILLIS + "ms.");
                }
            } finally {
                listening = null;
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        punishments.close();
        punishments.stream().close();
        running = false;
        Jedis open = listening;
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // Closing.
            }
        }
        listener.interrupt();
        pool.close();
    }
}
