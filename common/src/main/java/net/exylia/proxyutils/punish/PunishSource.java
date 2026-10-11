package net.exylia.proxyutils.punish;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A ban plugin installed on this proxy: its live punishments are already flowing into the stream
 * (it was hooked when it was found), and its history can be read once for an import.
 */
public interface PunishSource {

    /** {@code LITEBANS}, {@code LIBERTYBANS}, {@code ADVANCEDBAN} or {@code BANMANAGER}. */
    @NotNull String origin();

    /**
     * Reads every punishment the plugin has, oldest first where it can tell, page by page, handing
     * each to {@code sink}: an active one as {@code added}, a lifted or expired one as {@code added}
     * then {@code removed}. Runs on the import's own thread, so it may block.
     */
    void importAll(@NotNull Consumer<ExternalPunishment> sink) throws Exception;

    /** Stops listening to the plugin. */
    void close();
}
