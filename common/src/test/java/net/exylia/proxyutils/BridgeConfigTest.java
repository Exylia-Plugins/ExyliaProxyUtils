package net.exylia.proxyutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeConfigTest {

    @Test
    void anOlderFileKeepsRedisAndGainsTheTransportSection(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("config.properties");
        Files.writeString(file, "redis.host=10.0.0.6\nredis.key-prefix=net\n");
        BridgeConfig config = BridgeConfig.load(folder);
        assertFalse(config.byDatabase());
        assertEquals("10.0.0.6", config.host());
        String written = Files.readString(file);
        assertTrue(written.startsWith("redis.host=10.0.0.6\nredis.key-prefix=net\n"), "the owner's lines stay");
        assertTrue(written.contains("transport=redis"));
        BridgeConfig.load(folder);
        assertEquals(written, Files.readString(file), "appended once");
    }

    @Test
    void databaseTransportIsRead(@TempDir Path folder) throws Exception {
        BridgeConfig.load(folder);
        Path file = folder.resolve("config.properties");
        Files.writeString(file, Files.readString(file).replace("transport=redis", "transport=database")
                .replace("database.type=mysql", "database.type=postgres")
                .replace("database.port=0", "database.port=5433"));
        BridgeConfig config = BridgeConfig.load(folder);
        assertTrue(config.byDatabase());
        assertEquals("postgresql", config.target().type());
        assertEquals(5433, config.target().port());
        assertTrue(config.target().supported());
    }
}
