package net.exylia.proxyutils.punish;

import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The ban plugins this proxy has, and the imports of their history.
 *
 * <p>A platform main {@link #install}s each hook it can; a hook whose plugin is not there gives
 * null and is skipped. An import runs on a thread of its own, writing straight to the stream, and
 * logs its progress every {@value #PROGRESS} punishments.
 */
public final class Punishments implements AutoCloseable {

    private static final int PROGRESS = 1_000;

    private final Platform platform;
    private final PunishStream stream;
    private final Map<String, PunishSource> sources = new ConcurrentHashMap<>();
    private final Set<String> importing = ConcurrentHashMap.newKeySet();

    public Punishments(@NotNull Platform platform, @NotNull PunishStream stream) {
        this.platform = platform;
        this.stream = stream;
    }

    /** Where live punishments go. */
    public @NotNull PunishStream stream() {
        return stream;
    }

    /**
     * Hooks one plugin. {@code hook} checks the plugin is there before touching any of its classes
     * and returns null when it is not; a hook that throws is logged and skipped.
     */
    public void install(@NotNull String origin, @NotNull Supplier<@Nullable PunishSource> hook) {
        try {
            PunishSource source = hook.get();
            if (source != null) {
                sources.put(source.origin(), source);
                platform.info("Recording " + origin + " punishments made on this proxy.");
            }
        } catch (RuntimeException | LinkageError failure) {
            platform.warn("Could not hook " + origin + ": " + failure);
        }
    }

    /** The origins installed here, sorted. */
    public @NotNull Set<String> origins() {
        return new TreeSet<>(sources.keySet());
    }

    /**
     * Starts importing one plugin's history into the stream.
     *
     * @return null once started; otherwise why not
     */
    public @Nullable String startImport(@NotNull String origin) {
        PunishSource source = sources.get(origin);
        if (source == null) {
            return origin + " not installed";
        }
        if (!importing.add(origin)) {
            return origin + " import already running";
        }
        Thread thread = new Thread(() -> runImport(source), "ExyliaProxyUtils-Import-" + origin);
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    private void runImport(PunishSource source) {
        String origin = source.origin();
        int[] count = {0};
        platform.info("Importing " + origin + " history into the punishment stream...");
        try {
            source.importAll(punishment -> {
                try {
                    stream.write(punishment.asImport().toJson());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
                if (++count[0] % PROGRESS == 0) {
                    platform.info("Importing " + origin + ": " + count[0] + " entries so far.");
                }
            });
            platform.info("Imported " + origin + ": " + count[0] + " entries.");
        } catch (Exception | LinkageError failure) {
            platform.warn("The " + origin + " import stopped after " + count[0] + " entries: " + failure);
        } finally {
            importing.remove(origin);
        }
    }

    @Override
    public void close() {
        sources.values().forEach(source -> {
            try {
                source.close();
            } catch (RuntimeException | LinkageError ignored) {
                // Shutting down.
            }
        });
        sources.clear();
    }
}
