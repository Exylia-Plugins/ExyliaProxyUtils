package net.exylia.proxyutils.wire;

import net.exylia.proxyutils.BridgeConfig;
import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.XAddParams;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The wire over Redis, the recommended one.
 *
 * <h2>Two connection shapes, because Redis has two</h2>
 * Commands come from a pool. The subscription cannot: {@code SUBSCRIBE} takes
 * over its connection for as long as it listens, so it gets one of its own on
 * its own thread, with no read timeout, re-subscribing when it drops.
 */
public final class RedisWire implements Wire {

    private static final int TIMEOUT_MILLIS = 2_000;
    private static final long RECONNECT_DELAY_MILLIS = 2_000;

    private final Platform platform;
    private final JedisPool pool;
    private final HostAndPort address;
    private final DefaultJedisClientConfig config;
    private volatile boolean running = true;
    private volatile @Nullable Jedis listening;
    private @Nullable Thread listener;

    private RedisWire(Platform platform, JedisPool pool, HostAndPort address, DefaultJedisClientConfig config) {
        this.platform = platform;
        this.pool = pool;
        this.address = address;
        this.config = config;
    }

    /**
     * Connects and checks Redis answers.
     *
     * @throws RuntimeException if Redis cannot be reached
     */
    public static @NotNull RedisWire open(@NotNull Platform platform, @NotNull BridgeConfig settings) {
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
                .clientName("exylia-proxyutils")
                .ssl(settings.ssl());
        if (!settings.username().isEmpty()) {
            config.user(settings.username());
        }
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
        return new RedisWire(platform, pool, address, built);
    }

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    @Override
    public void listen(@NotNull String channel, @NotNull Consumer<String> handler) {
        JedisPubSub subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String from, String message) {
                handler.accept(message);
            }
        };
        Thread thread = new Thread(() -> {
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
                    return;
                }
            }
        }, "ExyliaProxyUtils-Redis");
        thread.setDaemon(true);
        listener = thread;
        thread.start();
    }

    @Override
    public @Nullable String get(@NotNull String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        }
    }

    @Override
    public void streamAdd(@NotNull String stream, @NotNull Map<String, String> fields, long maxLength) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(stream, XAddParams.xAddParams().maxLen(maxLength).approximateTrimming(), fields);
        }
    }

    @Override
    public @NotNull String describe() {
        return "Redis at " + address;
    }

    @Override
    public void close() {
        running = false;
        Jedis open = listening;
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // Closing.
            }
        }
        Thread thread = listener;
        if (thread != null) {
            thread.interrupt();
        }
        pool.close();
    }
}
