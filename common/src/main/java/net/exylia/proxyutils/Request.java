package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One request from a backend server, as a module sees it.
 *
 * @param module      the module it was addressed to
 * @param payload     what the server wrote; the format is the module's own
 * @param carrier     the player it is about, or null for a request about
 *                    nobody in particular — a console command, a lookup
 * @param carrierName their name as the proxy has it, or empty
 * @param server      the backend server it came from, by its {@code server-id},
 *                    which is also its name on the proxy
 */
public record Request(@NotNull String module, @NotNull String payload, @Nullable UUID carrier,
                      @NotNull String carrierName, @NotNull String server) {
}
