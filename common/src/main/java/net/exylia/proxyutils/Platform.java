package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
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
     * Finds a connected player by name or by id.
     *
     * @param nameOrId a name, matched the way the proxy matches names, or a uuid
     * @return the player and their server, or empty if nobody by that is connected
     */
    @NotNull Optional<ProxyPlayer> find(@NotNull String nameOrId);

    /**
     * Moves a player to a server.
     *
     * @param player who to move
     * @param server the server, by the proxy's name for it
     * @return {@code OK} once the proxy connected them (or they were already
     *         there); {@code REJECTED} starting with {@code no server} for a
     *         name the proxy does not know, or with the reason the connection
     *         was refused; a player who is gone is {@code REJECTED} too
     */
    @NotNull CompletableFuture<Reply> connect(@NotNull UUID player, @NotNull String server);

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
