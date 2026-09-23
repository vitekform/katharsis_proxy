package me.ganamaga.katharsis_proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots the packaged plugin on a real Velocity proxy.
 *
 * <p>This is the only test that exercises the shaded jar the way a server owner
 * will: Velocity's own plugin loader, Guice injection, the relocated JDBC driver
 * and pool, and the resources copied out of the jar. Unit tests run against
 * unrelocated classes and cannot catch a broken shade configuration.</p>
 *
 * <p>Set {@code -Dkatharsis.velocity.skip=true} to skip it, e.g. when offline.</p>
 */
class VelocityServerIT {

    private static final String METADATA_URL =
            "https://fill.papermc.io/v3/projects/velocity/versions/%s/builds/latest";

    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(3);
    private static final long STARTUP_TIMEOUT_SECONDS = 180L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 60L;

    /** The id in velocity-plugin.json, which Velocity uses to name the plugin's logger and data directory. */
    private static final String PLUGIN_ID = "katharsis";

    /** Velocity prints this once it is fully up. */
    private static final String READY_MARKER = "Done (";

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("the packaged plugin loads and runs on a real Velocity proxy")
    void pluginLoadsOnVelocity(@TempDir Path runDirectory) throws Exception {
        Assumptions.assumeFalse(Boolean.getBoolean("katharsis.velocity.skip"),
                "skipped via -Dkatharsis.velocity.skip");

        Path pluginJar = requiredPath("katharsis.plugin.jar");
        Path velocityJar = resolveVelocityJar();
        int port = freePort();

        prepareRunDirectory(runDirectory, pluginJar, port);
        List<String> log = runProxy(runDirectory, velocityJar);

        assertPluginStarted(log);
        assertDataFilesWritten(runDirectory.resolve("plugins/" + PLUGIN_ID));
        assertCleanShutdown(log);
    }

    // --- assertions --------------------------------------------------------

    private void assertPluginStarted(List<String> log) {
        // The trailing space stops a stale id with the same prefix from matching.
        assertLogged(log, "Loaded plugin " + PLUGIN_ID + " ");
        assertLogged(log, "Katharsis Proxy loaded successfully.");

        assertLogged(log, "Loaded 2 language(s): cs_CZ, en_US");
        assertLogged(log, "Connected to H2 file database at");
        assertLogged(log, "Player entities will be created on first join.");

        assertLogged(log, "Registered /lobby -> server 'lobby'.");
        assertLogged(log, "Registered /lifesteal -> server 'lifesteal'.");
        assertLogged(log, "No /language is registered on the proxy; the companion plugin provides the selector.");
        assertNotLogged(log, "Registered /language");
        assertLogged(log, "Serving language requests on the 'katharsis:language' plugin message channel.");
        assertLogged(log, "Logging chat reported on the 'katharsis:chat' plugin message channel.");
        assertLogged(log, "Caching player languages for 10 minute(s) after disconnect.");
        assertLogged(log, "Registered /link for linking Minecraft accounts to Discord.");
        assertLogged(log, "Caching account links for 10 minute(s) after disconnect.");
        assertLogged(log, "The Discord bot is off.");

        // No LimboAPI here: the plugin must still load, and fail closed.
        assertLogged(log, "LimboAPI is not installed, so players without a Mojang account have nowhere to log in");
        assertLogged(log, "Names held by a Mojang account are sent through Mojang's login and skip the password.");
        assertLogged(log, "Registered /login and /register.");

        // Proves the shade relocation is what actually runs: an unrelocated
        // build would log com.zaxxer.hikari instead.
        assertLogged(log, "me.ganamaga.katharsis_proxy.libs.hikari");

        List<String> problems = log.stream()
                .filter(line -> line.contains("[" + PLUGIN_ID + "]") || line.contains("me.ganamaga"))
                .filter(line -> line.contains("ERROR") || line.contains("Exception"))
                .toList();
        assertTrue(problems.isEmpty(), () -> "the plugin logged errors: " + problems);
    }

    private void assertDataFilesWritten(Path dataDirectory) {
        assertTrue(Files.isRegularFile(dataDirectory.resolve("config.yml")),
                "config.yml should be copied out of the jar");
        assertTrue(Files.isRegularFile(dataDirectory.resolve("lang/en_US.yml")),
                "en_US.yml should be copied out of the jar");
        assertTrue(Files.isRegularFile(dataDirectory.resolve("lang/cs_CZ.yml")),
                "cs_CZ.yml should be copied out of the jar");
        assertTrue(Files.isRegularFile(dataDirectory.resolve("katharsis.mv.db")),
                "the H2 database file should be created");
    }

    private void assertCleanShutdown(List<String> log) {
        assertLogged(log, "Closed the database connection pool.");
    }

    private void assertNotLogged(List<String> log, String fragment) {
        List<String> offending = log.stream().filter(line -> line.contains(fragment)).toList();
        assertTrue(offending.isEmpty(),
                () -> "the proxy should not register a language command, but logged: " + offending);
    }

    private void assertLogged(List<String> log, String fragment) {
        assertTrue(log.stream().anyMatch(line -> line.contains(fragment)),
                () -> "expected a log line containing \"" + fragment + "\", but the proxy printed:\n"
                        + String.join("\n", log));
    }

    // --- proxy lifecycle ---------------------------------------------------

    /** Starts the proxy, waits until it is up, then shuts it down and returns its output. */
    private List<String> runProxy(Path runDirectory, Path velocityJar) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512M",
                "-jar", velocityJar.toAbsolutePath().toString());
        builder.directory(runDirectory.toFile());
        builder.redirectErrorStream(true);

        Process proxy = builder.start();
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);

        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proxy.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.add(line);
                    System.out.println("[velocity] " + line);
                    if (line.contains(READY_MARKER)) {
                        ready.countDown();
                    }
                }
            } catch (IOException e) {
                // The stream closes when the proxy exits; nothing to do.
            }
        }, "velocity-output");
        drain.setDaemon(true);
        drain.start();

        try {
            if (!ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("Velocity did not report \"" + READY_MARKER + "\" within "
                        + STARTUP_TIMEOUT_SECONDS + "s. Output so far:\n" + String.join("\n", log));
            }
            shutDown(proxy);
        } finally {
            if (proxy.isAlive()) {
                proxy.destroyForcibly();
                proxy.waitFor(30, TimeUnit.SECONDS);
            }
        }

        drain.join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(0, proxy.exitValue(), "the proxy should exit cleanly");
        return List.copyOf(log);
    }

    /** Asks the proxy to stop through its console, the way an operator would. */
    private void shutDown(Process proxy) throws Exception {
        try (BufferedWriter console = new BufferedWriter(
                new OutputStreamWriter(proxy.getOutputStream(), StandardCharsets.UTF_8))) {
            console.write("end\n");
            console.flush();
        } catch (IOException e) {
            // The proxy may already be on its way down.
        }
        if (!proxy.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            fail("Velocity did not shut down within " + SHUTDOWN_TIMEOUT_SECONDS + "s");
        }
    }

    // --- fixture -----------------------------------------------------------

    private void prepareRunDirectory(Path runDirectory, Path pluginJar, int port) throws IOException {
        Path plugins = runDirectory.resolve("plugins");
        Files.createDirectories(plugins);
        Files.copy(pluginJar, plugins.resolve(pluginJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        // Both backends are declared so the plugin registers /lobby and
        // /lifesteal without the "no such server" warning. Nothing listens on
        // them; this test never connects a client.
        Files.writeString(runDirectory.resolve("velocity.toml"), """
                config-version = "2.8"
                bind = "127.0.0.1:%d"
                motd = "Katharsis integration test"
                show-max-players = 10
                online-mode = false
                force-key-authentication = false
                prevent-client-proxy-connections = false
                player-info-forwarding-mode = "NONE"
                forwarding-secret-file = "forwarding.secret"
                announce-forge = false

                [servers]
                lobby = "127.0.0.1:%d"
                lifesteal = "127.0.0.1:%d"
                try = ["lobby"]

                [forced-hosts]

                [advanced]

                [query]
                enabled = false
                """.formatted(port, port + 1, port + 2));
        Files.writeString(runDirectory.resolve("forwarding.secret"), "katharsis-integration-test");
    }

    // --- Velocity download -------------------------------------------------

    /**
     * Returns the Velocity jar, downloading it into the build directory the
     * first time and reusing it afterwards.
     */
    private Path resolveVelocityJar() throws Exception {
        String version = System.getProperty("katharsis.velocity.version", "3.5.1");
        Path cache = Path.of(System.getProperty("katharsis.velocity.cache", "target/velocity"));
        Files.createDirectories(cache);

        JsonObject download = fetchBuildMetadata(version);
        String fileName = download.get("name").getAsString();
        String expectedSha = download.getAsJsonObject("checksums").get("sha256").getAsString();
        Path jar = cache.resolve(fileName);

        if (Files.exists(jar) && expectedSha.equals(sha256(jar))) {
            return jar;
        }

        Path temporary = Files.createTempFile(cache, "velocity", ".part");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(download.get("url").getAsString()))
                    .timeout(HTTP_TIMEOUT)
                    .build();
            HttpResponse<Path> response =
                    client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            assertEquals(200, response.statusCode(), "downloading " + fileName);
        }

        String actualSha = sha256(temporary);
        assertEquals(expectedSha, actualSha, "checksum of the downloaded " + fileName);
        Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    private JsonObject fetchBuildMetadata(String version) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(METADATA_URL.formatted(version)))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(),
                    () -> "looking up the latest Velocity " + version + " build: " + response.body());

            return JsonParser.parseString(response.body())
                    .getAsJsonObject()
                    .getAsJsonObject("downloads")
                    .getAsJsonObject("server:default");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    // --- helpers -----------------------------------------------------------

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            throw new IllegalStateException(property + " is not set; run this test through Maven (mvn verify)");
        }
        Path path = Path.of(value);
        assertTrue(Files.isRegularFile(path), () -> property + " does not exist: " + path);
        return path;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
