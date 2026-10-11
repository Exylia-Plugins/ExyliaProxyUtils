package net.exylia.proxyutils.punish;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One punishment a ban plugin made, as ExyliaStaff reads it from
 * {@code <prefix>:punish:external}.
 *
 * <p>{@link #toJson()} writes the stream's {@code json} field, version 1; ExyliaStaff's consumer reads
 * exactly this, so a change here is a change there. Times are epoch milliseconds, 0 for never.
 *
 * @param kind     {@code added} or {@code removed}
 * @param origin   {@code LITEBANS}, {@code LIBERTYBANS}, {@code ADVANCEDBAN} or {@code BANMANAGER}
 * @param originId stable per origin, table-qualified where the plugin numbers each table on its own
 * @param type     {@code BAN TEMPBAN IPBAN MUTE TEMPMUTE WARN KICK}
 * @param imported produced by an import: no alerts, no player notice
 */
public record ExternalPunishment(@NotNull String kind, @NotNull String origin, @NotNull String originId,
                                 @NotNull String type, @NotNull UUID target, @Nullable String targetName,
                                 @Nullable UUID staff, @Nullable String staffName, @Nullable String reason,
                                 @NotNull String server, long created, long expires, long removedAt,
                                 @Nullable String removedBy, @Nullable String removeReason,
                                 boolean imported) {

    public static final String ADDED = "added";
    public static final String REMOVED = "removed";

    /** What a punishment made on the proxy itself names as its server. */
    public static final String PROXY = "proxy";

    /** A punishment that was just made. */
    public static @NotNull ExternalPunishment added(@NotNull String origin, @NotNull String originId,
                                                    @NotNull String type, @NotNull UUID target,
                                                    @Nullable String targetName, @Nullable UUID staff,
                                                    @Nullable String staffName, @Nullable String reason,
                                                    @Nullable String server, long created, long expires) {
        return new ExternalPunishment(ADDED, origin, originId, type, target, targetName, staff, staffName,
                reason, server == null || server.isBlank() ? PROXY : server, created,
                Math.max(0, expires), 0, null, null, false);
    }

    /** The same punishment, lifted. */
    public @NotNull ExternalPunishment removed(long at, @Nullable String by, @Nullable String why) {
        return new ExternalPunishment(REMOVED, origin, originId, type, target, targetName, staff, staffName,
                reason, server, created, expires, at, by, why, imported);
    }

    /** The same punishment, read from history rather than seen happen. */
    public @NotNull ExternalPunishment asImport() {
        return new ExternalPunishment(kind, origin, originId, type, target, targetName, staff, staffName,
                reason, server, created, expires, removedAt, removedBy, removeReason, true);
    }

    /**
     * The contract's type from a plugin's family ({@code ban mute warn kick}), or null for a family
     * that is not a punishment (notes).
     */
    public static @Nullable String type(@Nullable String family, boolean temporary, boolean ip) {
        if (family == null) {
            return null;
        }
        return switch (family) {
            case "ban" -> ip ? "IPBAN" : temporary ? "TEMPBAN" : "BAN";
            case "mute" -> temporary ? "TEMPMUTE" : "MUTE";
            case "warn", "warning" -> "WARN";
            case "kick" -> "KICK";
            default -> null;
        };
    }

    /** The stream's {@code json} field. */
    public @NotNull String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("kind", kind);
        json.addProperty("origin", origin);
        json.addProperty("originId", cut(originId, 64));
        json.addProperty("type", type);
        json.addProperty("target", target.toString());
        json.addProperty("targetName", targetName);
        json.addProperty("staff", staff == null ? null : staff.toString());
        json.addProperty("staffName", staffName);
        json.addProperty("reason", cut(reason == null ? "" : reason, 255));
        json.addProperty("server", server);
        json.addProperty("created", created);
        json.addProperty("expires", expires);
        json.addProperty("removedAt", removedAt);
        json.addProperty("removedBy", removedBy);
        json.addProperty("removeReason", removeReason == null ? null : cut(removeReason, 255));
        json.addProperty("import", imported);
        return json.toString();
    }

    /**
     * A uuid from what a ban plugin stores: dashed or not; null for the console's "CONSOLE",
     * an address in an IP entry's place, or nothing.
     */
    public static @Nullable UUID uuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String hex = value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
