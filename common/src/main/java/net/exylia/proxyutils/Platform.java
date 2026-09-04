package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What the bridge needs from the proxy it runs on.
 *
 * <p>Velocity and BungeeCord each implement this once; nothing else in the
 * plugin names either of them.
 */
public interface Platform {

    /** The proxy software, as a player-facing word: {@code Velocity}, {@code BungeeCord}. */
    @NotNull String name();

    /** This plugin's version. */
    @NotNull String version();

    void info(@NotNull String message);

    void warn(@NotNull String message);

    /**
     * Runs a command as a player, with that player's permissions.
     *
     * @param player  who runs it
     * @param command the command without a leading slash
     * @return whether the proxy found and ran the command; false for a player
     *         who is no longer connected
     */
    @NotNull CompletableFuture<Boolean> runAsPlayer(@NotNull UUID player, @NotNull String command);

    /**
     * Runs a command as the proxy console.
     *
     * @param command the command without a leading slash
     * @return whether the proxy found and ran the command
     */
    @NotNull CompletableFuture<Boolean> runAsConsole(@NotNull String command);

    /**
     * Sends bytes to the server a player is on, on the bridge channel.
     *
     * @param carrier the player whose connection carries them
     * @param data    the bytes
     * @return false if the player is gone or between servers, in which case
     *         nothing was sent
     */
    boolean reply(@NotNull UUID carrier, byte @NotNull [] data);
}
