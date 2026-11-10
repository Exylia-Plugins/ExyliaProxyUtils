package net.exylia.proxyutils.wire;

import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A wire kept in the backends' database ({@code cross-server: database}).
 *
 * <p>The other side of ExyliaLib's {@code DatabaseBus}: the same tables, the
 * same message numbering, the same two-second lookback, so a number that
 * commits after a higher one is still delivered, once. See ExyliaLib
 * {@code docs/redis.md}, "Without a Redis server".
 */
abstract class PollingWire implements Wire {

    static final long POLL_MILLIS = 250;
    static final long LOOKBACK_MILLIS = 2_000;
    static final long KEEP_MILLIS = 60_000;
    static final long PURGE_EVERY_MILLIS = 15_000;
    static final long RETRY_MILLIS = 2_000;
    static final int PAGE = 500;

    static final String MESSAGES = "exylia_bus_messages";
    static final String KEYS = "exylia_bus_keys";
    static final String STREAMS = "exylia_bus_streams";
    static final String ENTRIES = "exylia_bus_entries";
    static final String GROUPS = "exylia_bus_groups";
    static final String PENDING = "exylia_bus_pending";

    /** A message as read back. */
    record Envelope(long id, @NotNull String payload) {
    }

    protected final Platform platform;
    private volatile boolean running = true;
    private Thread poller;

    // Poller thread only.
    private final ArrayDeque<long[]> marks = new ArrayDeque<>();
    private final Map<Long, Boolean> delivered = new HashMap<>();
    private long newest;
    private long lastPurge;

    PollingWire(@NotNull Platform platform) {
        this.platform = platform;
    }

    /** The highest message number now. */
    abstract long newestId() throws Exception;

    /** Messages on one channel numbered above {@code after}, lowest first. */
    abstract @NotNull List<Envelope> readAfter(@NotNull String channel, long after, int limit) throws Exception;

    /** Deletes messages older than {@code before}. Expired keys are the backends' to purge. */
    abstract void purge(long before) throws Exception;

    /** Waits for the next poll; Postgres returns early on a notification. */
    void await(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    abstract void closeStore();

    @Override
    public void listen(@NotNull String channel, @NotNull Consumer<String> handler) {
        prime();
        poller = new Thread(() -> run(channel, handler), "ExyliaProxyUtils-Bus");
        poller.setDaemon(true);
        poller.start();
    }

    /** Where new messages start; a test calls it and drives {@link #poll} itself. */
    final void prime() {
        try {
            newest = newestId();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
        long now = System.currentTimeMillis();
        marks.add(new long[]{now, newest});
        lastPurge = now;
    }

    private void run(String channel, Consumer<String> handler) {
        boolean warned = false;
        while (running) {
            try {
                await(POLL_MILLIS);
                if (!running) {
                    return;
                }
                poll(channel, handler, System.currentTimeMillis());
                warned = false;
            } catch (InterruptedException interrupted) {
                return;
            } catch (Throwable failure) {
                if (!warned && running) {
                    warned = true;
                    platform.warn("The cross-server database is not answering (" + failure.getMessage()
                            + "); retrying every " + RETRY_MILLIS + "ms.");
                }
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    final void poll(String channel, Consumer<String> handler, long now) throws Exception {
        long floor = floor(now);
        long after = floor;
        List<Envelope> page;
        do {
            page = readAfter(channel, after, PAGE);
            for (Envelope envelope : page) {
                after = envelope.id();
                newest = Math.max(newest, envelope.id());
                if (delivered.putIfAbsent(envelope.id(), Boolean.TRUE) == null) {
                    try {
                        handler.accept(envelope.payload());
                    } catch (Throwable failure) {
                        platform.warn("A request failed: " + failure);
                    }
                }
            }
        } while (page.size() == PAGE && running);
        marks.addLast(new long[]{now, newest});
        delivered.keySet().removeIf(id -> id <= floor);
        if (now - lastPurge >= PURGE_EVERY_MILLIS) {
            lastPurge = now;
            purge(now - KEEP_MILLIS);
        }
    }

    private long floor(long now) {
        long cutoff = now - LOOKBACK_MILLIS;
        while (marks.size() >= 2) {
            Iterator<long[]> iterator = marks.iterator();
            iterator.next();
            if (iterator.next()[0] > cutoff) {
                break;
            }
            marks.removeFirst();
        }
        return marks.peekFirst()[1];
    }

    @Override
    public void close() {
        running = false;
        Thread thread = poller;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        closeStore();
    }
}
