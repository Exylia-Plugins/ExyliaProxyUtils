package net.exylia.proxyutils;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @Test
    void comparesVersions() {
        assertTrue(Updater.isNewer("2.0.2", "2.0.1"));
        assertTrue(Updater.isNewer("2.1.0", "2.0.9"));
        assertFalse(Updater.isNewer("2.0.1", "2.0.1"));
        assertFalse(Updater.isNewer("2.0.0", "2.0.1"));
        assertFalse(Updater.isNewer("garbage", "2.0.1"));
    }

    @Test
    void readsTheVersionOnlyFromThisPlugin() throws Exception {
        Path dir = Files.createTempDirectory("updater");
        assertEquals("2.0.2", Updater.versionOf(jar(dir.resolve("a.jar"),
                "{\"id\":\"exyliaproxyutils\",\"name\":\"ExyliaProxyUtils\",\"version\":\"2.0.2\"}")));
        assertNull(Updater.versionOf(jar(dir.resolve("b.jar"), "{\"id\":\"other\",\"version\":\"2.0.2\"}")));
        Files.writeString(dir.resolve("c.jar"), "not a zip");
        assertNull(Updater.versionOf(dir.resolve("c.jar")));
    }

    private static Path jar(Path path, String descriptor) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("velocity-plugin.json"));
            out.write(descriptor.getBytes());
        }
        return path;
    }
}
