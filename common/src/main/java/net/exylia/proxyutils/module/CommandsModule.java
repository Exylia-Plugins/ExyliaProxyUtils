package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Platform;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Runs a command on the proxy.
 *
 * <p>The payload is {@code <actor>:<command>}, the same shape every Exylia
 * config already writes: {@code player-proxy:server lobby} runs as the player
 * carrying the request, with their permissions; {@code console-proxy:alert hi}
 * runs as the proxy console, and is logged, because a backend server just ran
 * something as root on the proxy and the log is where that belongs.
 *
 * <p>A command the proxy does not have is {@link Reply.Status#REJECTED} with
 * its name, so a typo in a YAML file is read in the console rather than
 * guessed at.
 */
public final class CommandsModule implements BridgeModule {

    public static final String PLAYER = "player-proxy";
    public static final String CONSOLE = "console-proxy";

    private final Platform platform;

    public CommandsModule(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    public @NotNull String name() {
        return "commands";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        String payload = request.payload();
        int colon = payload.indexOf(':');
        if (colon <= 0) {
            return CompletableFuture.completedFuture(
                    Reply.rejected("expected <actor>:<command>, got \"" + payload + "\""));
        }
        String actor = payload.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String command = payload.substring(colon + 1).trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return CompletableFuture.completedFuture(Reply.rejected("empty command"));
        }
        CompletableFuture<Boolean> ran = switch (actor) {
            case PLAYER -> platform.runAsPlayer(request.carrier(), command);
            case CONSOLE -> {
                platform.info("[" + request.server() + "] console: /" + command
                        + " (through " + request.carrierName() + ")");
                yield platform.runAsConsole(command);
            }
            default -> null;
        };
        if (ran == null) {
            return CompletableFuture.completedFuture(Reply.rejected("unknown actor \"" + actor
                    + "\"; use " + PLAYER + " or " + CONSOLE));
        }
        String label = command.split(" ", 2)[0];
        return ran.thenApply(found -> found
                ? Reply.ok()
                : Reply.rejected("the proxy did not run \"/" + label + "\""));
    }
}
