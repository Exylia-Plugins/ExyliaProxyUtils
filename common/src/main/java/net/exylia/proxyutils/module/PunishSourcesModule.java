package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import net.exylia.proxyutils.punish.Punishments;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Which ban plugins this proxy has: {@code OK} with their origins comma-separated
 * ({@code LITEBANS,LIBERTYBANS}), empty for none. ExyliaStaff asks before an import, to know
 * whether the proxy can do it.
 */
public final class PunishSourcesModule implements BridgeModule {

    private final Punishments punishments;

    public PunishSourcesModule(@NotNull Punishments punishments) {
        this.punishments = punishments;
    }

    @Override
    public @NotNull String name() {
        return "punish-sources";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        return CompletableFuture.completedFuture(Reply.ok(String.join(",", punishments.origins())));
    }
}
