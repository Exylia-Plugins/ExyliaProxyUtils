package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Tells a backend server something it did not ask for. */
public interface Publisher {

    /**
     * Sends a push to one server.
     *
     * @param server  the server, by its {@code server-id}
     * @param module  which handler on that server should get it
     * @param carrier the player it is about, or null
     * @param payload what to say
     */
    void push(@NotNull String server, @NotNull String module, @Nullable UUID carrier,
              @NotNull String payload);
}
