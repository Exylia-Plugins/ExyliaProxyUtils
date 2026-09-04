package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One request from a backend server, as a module sees it.
 *
 * @param module      the module it was addressed to
 * @param payload     what the server wrote; the format is the module's own
 * @param carrier     the player whose connection carried it
 * @param carrierName their name, for logs and for proxies that know players by name
 * @param server      the backend server it came from, by the proxy's name for it
 */
public record Request(@NotNull String module, @NotNull String payload, @NotNull UUID carrier,
                      @NotNull String carrierName, @NotNull String server) {
}
