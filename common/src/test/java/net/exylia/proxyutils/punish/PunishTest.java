package net.exylia.proxyutils.punish;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishTest {

    private static final UUID TARGET = UUID.nameUUIDFromBytes("target".getBytes());

    @Test
    @DisplayName("the stream entry is the contract's v1 JSON, nulls included")
    void json() {
        ExternalPunishment added = ExternalPunishment.added("LITEBANS", "ban:12", "TEMPBAN", TARGET, "Steve",
                null, "Console", "x".repeat(300), null, 1_000, 2_000);
        JsonObject json = JsonParser.parseString(added.removed(1_500, "Mod", "appeal").asImport().toJson())
                .getAsJsonObject();
        assertEquals(1, json.get("v").getAsInt());
        assertEquals("removed", json.get("kind").getAsString());
        assertEquals("ban:12", json.get("originId").getAsString());
        assertEquals(TARGET.toString(), json.get("target").getAsString());
        assertTrue(json.get("staff").isJsonNull(), "the console has no uuid");
        assertEquals(255, json.get("reason").getAsString().length());
        assertEquals("proxy", json.get("server").getAsString());
        assertEquals(2_000, json.get("expires").getAsLong());
        assertEquals(1_500, json.get("removedAt").getAsLong());
        assertEquals("Mod", json.get("removedBy").getAsString());
        assertTrue(json.get("import").getAsBoolean());
        assertEquals(17, json.size(), "every field of the contract, no more");
    }

    @Test
    @DisplayName("plugin families map to the contract's types; notes are not punishments")
    void types() {
        assertEquals("IPBAN", ExternalPunishment.type("ban", true, true));
        assertEquals("TEMPBAN", ExternalPunishment.type("ban", true, false));
        assertEquals("MUTE", ExternalPunishment.type("mute", false, true));
        assertEquals("WARN", ExternalPunishment.type("warning", false, false));
        assertNull(ExternalPunishment.type("note", false, false));
        assertEquals(TARGET, ExternalPunishment.uuid(TARGET.toString().replace("-", "")));
        assertNull(ExternalPunishment.uuid("CONSOLE"));
        assertNull(ExternalPunishment.uuid("1.2.3.4"));
    }

    @Test
    @DisplayName("mirror keys: lowercase uuid, sha256 hex of the address as text")
    void mirrorKeys() {
        assertEquals("exylia:punish:ban:" + TARGET, BanMirror.banKey("exylia", TARGET));
        assertEquals("exylia:punish:ipban:6694f83c9f476da31f5df6bcc520034e7e57d421d247b9d34f49edbfc84a764c",
                BanMirror.ipBanKey("exylia", "1.2.3.4"));
    }

    @Test
    @DisplayName("a mirror entry denies until it expires; garbage and nothing let the player in")
    void mirrorEntries() {
        String entry = "{\"id\":123,\"type\":\"BAN\",\"expires\":%d,\"mini\":\"<red>No\",\"legacy\":\"§cNo\"}";
        assertEquals(Optional.of(new BanMirror.Denial(123, "<red>No", "§cNo")),
                BanMirror.parse(entry.formatted(0), 5_000), "0 is permanent");
        assertTrue(BanMirror.parse(entry.formatted(6_000), 5_000).isPresent());
        assertTrue(BanMirror.parse(entry.formatted(5_000), 5_000).isEmpty(), "expired");
        assertTrue(BanMirror.parse("not json", 5_000).isEmpty());
        assertTrue(BanMirror.parse(null, 5_000).isEmpty());
    }
}
