package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

/**
 * One backend the proxy has registered, as last pinged.
 *
 * @param name    the proxy's name for it
 * @param players how many players the proxy has connected to it
 * @param online  whether it answered a ping in time
 */
public record ServerStatus(@NotNull String name, int players, boolean online) {
}
