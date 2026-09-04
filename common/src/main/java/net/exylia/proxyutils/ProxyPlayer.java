package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player the proxy has, and where.
 *
 * @param id     their id
 * @param name   their name as the proxy has it
 * @param server the backend they are on, by the proxy's name for it; empty
 *               while they are between servers
 */
public record ProxyPlayer(@NotNull UUID id, @NotNull String name, @NotNull String server) {

    /** How the {@code player} module writes it: {@code uuid|name|server}. */
    public @NotNull String toWire() {
        return id + "|" + name + "|" + server;
    }
}
