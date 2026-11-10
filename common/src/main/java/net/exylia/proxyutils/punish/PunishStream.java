package net.exylia.proxyutils.punish;

import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;
import net.exylia.proxyutils.wire.Wire;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Writes {@link ExternalPunishment}s to the stream {@code <prefix>:punish:external}, where
 * ExyliaStaff reads them with a consumer group.
 *
 * <p>{@code XADD <key> MAXLEN ~ 200000 * json <json>}. Live punishments go through one writer
 * thread, so they never wait on Redis on a ban plugin's thread and an {@code added} is never
 * overtaken by its {@code removed}. A write is tried three times; one that still fails is logged
 * with its JSON, never dropped silently.
 */
public final class PunishStream implements AutoCloseable {

    private static final long MAX_LENGTH = 200_000;
    private static final int ATTEMPTS = 3;

    private final Platform platform;
    private final Wire wire;
    private final String key;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ExyliaProxyUtils-Punish");
        thread.setDaemon(true);
        return thread;
    });

    public PunishStream(@NotNull Platform platform, @NotNull Wire wire, @NotNull String prefix) {
        this.platform = platform;
        this.wire = wire;
        this.key = key(prefix);
    }

    /** The stream's key. */
    public static @NotNull String key(@NotNull String prefix) {
        return prefix + ":punish:external";
    }

    /** Queues a punishment seen live; returns at once. */
    public void add(@NotNull ExternalPunishment punishment) {
        String json = punishment.toJson();
        writer.execute(() -> {
            try {
                write(json);
            } catch (RuntimeException | InterruptedException lost) {
                platform.warn("Could not record a punishment in " + key + " (" + lost.getMessage()
                        + "): " + json);
            }
        });
    }

    /**
     * Writes one entry now, on the caller's thread; what an import uses.
     *
     * @throws RuntimeException if Redis refused it {@value #ATTEMPTS} times
     */
    public void write(@NotNull String json) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                wire.streamAdd(key, Map.of("json", json), MAX_LENGTH);
                return;
            } catch (RuntimeException failure) {
                last = failure;
                if (attempt < ATTEMPTS) {
                    Thread.sleep(1_000L * attempt);
                }
            }
        }
        throw last;
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
