package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * One thing a backend server can ask the proxy to do.
 *
 * <p>Adding a capability to the network is one of these, registered with
 * {@link Bridge#register}, and one {@code Proxy.request(player, name, payload)}
 * in ExyliaLib. The module reads its payload however it likes; the bridge
 * handles framing, routing and getting the answer back to the right server.
 *
 * <p>A module runs on the proxy's network thread. Anything slow returns a
 * future and finishes elsewhere; a module that throws answers
 * {@link Reply.Status#FAILED} rather than taking the thread down.
 */
public interface BridgeModule {

    /** The name servers address this module by; lowercase. */
    @NotNull String name();

    /**
     * Handles one request.
     *
     * @param request what was asked, and by whom
     * @return the answer, sent back to the server the request came from
     */
    @NotNull CompletableFuture<Reply> handle(@NotNull Request request);
}
