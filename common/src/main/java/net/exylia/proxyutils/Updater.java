package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Keeps the jar up to date the way ExyliaLib keeps the backends': every release attaches
 * {@code ExyliaProxyUtils.jar} under a name that never changes, so
 * {@code releases/latest/download/ExyliaProxyUtils.jar} redirects to the newest one and the
 * redirect says its version without downloading anything.
 *
 * <p>A newer release of the same major is downloaded next to the running jar, checked to be this
 * plugin at that version, and moved over it. The proxy keeps running the classes it already
 * opened; the next start loads the new jar. A new major may change the config or the protocol,
 * so it is only reported.
 */
public final class Updater {

    private static final String LATEST =
            "https://github.com/Exylia-Plugins/ExyliaProxyUtils/releases/latest/download/ExyliaProxyUtils.jar";
    private static final Pattern RELEASE = Pattern.compile("/releases/download/v(\\d+\\.\\d+\\.\\d+)/");
    private static final Pattern DESCRIPTOR_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Updater() {
    }

    /** Checks once and installs a newer release for the next start; never throws. */
    public static void check(@NotNull Platform platform) {
        try {
            Path jar = runningJar();
            if (jar == null) {
                return;
            }
            String current = platform.version();
            String latest = latest();
            if (latest == null || !isNewer(latest, current) || latest.equals(versionOf(jar))) {
                return;
            }
            if (major(latest) != major(current)) {
                platform.warn("ExyliaProxyUtils " + latest + " is a new major version; install it by hand when ready.");
                return;
            }
            Path tmp = Files.createTempFile(jar.getParent(), "ExyliaProxyUtils", ".tmp");
            try {
                download("https://github.com/Exylia-Plugins/ExyliaProxyUtils/releases/download/v"
                        + latest + "/ExyliaProxyUtils.jar", tmp);
                if (!latest.equals(versionOf(tmp))) {
                    throw new IOException("the download is not ExyliaProxyUtils " + latest);
                }
                try {
                    Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException notAtomic) {
                    Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            platform.info("ExyliaProxyUtils " + latest + " installed (running " + current
                    + "); it loads on the next restart.");
        } catch (IOException | RuntimeException failure) {
            platform.warn("Could not check for ExyliaProxyUtils updates: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The jar this class was loaded from, or {@code null} when it is not a jar (an IDE run). */
    private static @Nullable Path runningJar() {
        try {
            Path path = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? path : null;
        } catch (Exception unknown) {
            return null;
        }
    }

    /** The newest release's version, read from where the fixed-name asset redirects. */
    private static @Nullable String latest() throws IOException, InterruptedException {
        HttpResponse<Void> response = HTTP.send(request(LATEST).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.discarding());
        String location = response.headers().firstValue("location").orElse(null);
        if (response.statusCode() / 100 != 3 || location == null) {
            throw new IOException("no release with ExyliaProxyUtils.jar (HTTP " + response.statusCode() + ")");
        }
        Matcher matcher = RELEASE.matcher(location);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void download(String url, Path into) throws IOException, InterruptedException {
        // The release asset itself redirects to GitHub's storage; follow those hops here.
        for (int hop = 0; hop < 5; hop++) {
            HttpResponse<Path> response = HTTP.send(request(url).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(into));
            if (response.statusCode() == 200) {
                return;
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (response.statusCode() / 100 != 3 || location == null) {
                throw new IOException("download returned HTTP " + response.statusCode());
            }
            url = URI.create(url).resolve(location).toString();
        }
        throw new IOException("too many redirects");
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ExyliaProxyUtils-Updater");
    }

    /** The version a jar's velocity-plugin.json declares, or {@code null} for anything else. */
    static @Nullable String versionOf(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry("velocity-plugin.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (!json.contains("\"exyliaproxyutils\"")) {
                    return null;
                }
                Matcher matcher = DESCRIPTOR_VERSION.matcher(json);
                return matcher.find() ? matcher.group(1) : null;
            }
        } catch (IOException notAJar) {
            return null;
        }
    }

    static boolean isNewer(String candidate, String current) {
        int[] a = triple(candidate);
        int[] b = triple(current);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    private static int major(String version) {
        int[] parts = triple(version);
        return parts == null ? -1 : parts[0];
    }

    private static int @Nullable [] triple(String version) {
        String[] parts = version.split("-", 2)[0].split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException notAVersion) {
            return null;
        }
    }
}
