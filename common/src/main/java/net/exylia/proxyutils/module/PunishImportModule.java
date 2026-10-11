package net.exylia.proxyutils.module;

import net.exylia.proxyutils.BridgeModule;
import net.exylia.proxyutils.Reply;
import net.exylia.proxyutils.Request;
import net.exylia.proxyutils.punish.Punishments;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Imports the whole history of a ban plugin on this proxy into {@code <prefix>:punish:external},
 * every entry marked {@code import:true}. The payload is the origin ({@code LITEBANS}); the answer
 * is {@code OK started <ORIGIN>} at once and the import runs on its own thread, logging its
 * progress; {@code REJECTED <ORIGIN> not installed} when the proxy does not have that plugin.
 */
public final class PunishImportModule implements BridgeModule {

    private final Punishments punishments;

    public PunishImportModule(@NotNull Punishments punishments) {
        this.punishments = punishments;
    }

    @Override
    public @NotNull String name() {
        return "punish-import";
    }

    @Override
    public @NotNull CompletableFuture<Reply> handle(@NotNull Request request) {
        String origin = request.payload().trim().toUpperCase(Locale.ROOT);
        String refused = punishments.startImport(origin);
        return CompletableFuture.completedFuture(refused == null
                ? Reply.ok("started " + origin) : Reply.rejected(refused));
    }
}
