package net.exylia.proxyutils.punish;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.proxyutils.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * ExyliaStaff's own bans, read at login from the mirror it keeps in Redis.
 *
 * <p>{@code <prefix>:punish:ban:<uuid>} and {@code <prefix>:punish:ipban:<sha256 hex of the address>}
 * each hold {@code {"id","type","expires","mini","legacy"}}. ExyliaStaff writes, expires and deletes
 * them; this only reads, and still ignores one whose {@code expires} has passed. Redis being down
 * lets everybody in, with a warning at most once a minute: the backends check their own database
 * too, so this is an accelerator and the proxy-level gate, never the only one.
 */
public final class BanMirror {

    private static final long WARN_EVERY_MILLIS = 60_000;

    private final Platform platform;
    private final JedisPool pool;
    private final String prefix;
    private volatile long lastWarning;

    /**
     * What a banned player is shown.
     *
     * @param mini   the screen in MiniMessage, for Velocity
     * @param legacy the screen in §-codes, for BungeeCord
     */
    public record Denial(long id, @NotNull String mini, @NotNull String legacy) {
    }

    public BanMirror(@NotNull Platform platform, @NotNull JedisPool pool, @NotNull String prefix) {
        this.platform = platform;
        this.pool = pool;
        this.prefix = prefix;
    }

    /**
     * Whether this player or address is banned. Blocks for at most the pool's wait plus the socket
     * timeout: call it from an async login task, never the network thread.
     *
     * @param address the address as text, {@code InetAddress.getHostAddress()}
     */
    public @NotNull Optional<Denial> check(@NotNull UUID player, @Nullable String address) {
        try (Jedis jedis = pool.getResource()) {
            long now = System.currentTimeMillis();
            Optional<Denial> denial = parse(jedis.get(banKey(prefix, player)), now);
            if (denial.isEmpty() && address != null && !address.isEmpty()) {
                denial = parse(jedis.get(ipBanKey(prefix, address)), now);
            }
            return denial;
        } catch (RuntimeException down) {
            long now = System.currentTimeMillis();
            if (now - lastWarning >= WARN_EVERY_MILLIS) {
                lastWarning = now;
                platform.warn("Could not read the ban mirror (" + down.getMessage()
                        + "); letting players in, the backends still check.");
            }
            return Optional.empty();
        }
    }

    public static @NotNull String banKey(@NotNull String prefix, @NotNull UUID player) {
        return prefix + ":punish:ban:" + player.toString().toLowerCase(Locale.ROOT);
    }

    public static @NotNull String ipBanKey(@NotNull String prefix, @NotNull String address) {
        return prefix + ":punish:ipban:" + sha256(address);
    }

    static @NotNull String sha256(@NotNull String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A mirror entry, or empty for none, an expired one, or one that is not JSON. */
    static @NotNull Optional<Denial> parse(@Nullable String json, long now) {
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject entry = JsonParser.parseString(json).getAsJsonObject();
            long expires = number(entry, "expires");
            if (expires > 0 && expires <= now) {
                return Optional.empty();
            }
            return Optional.of(new Denial(number(entry, "id"), text(entry, "mini"), text(entry, "legacy")));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static long number(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static String text(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
