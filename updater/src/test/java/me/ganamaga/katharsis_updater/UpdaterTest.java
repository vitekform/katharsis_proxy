package me.ganamaga.katharsis_updater;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    private static final String[] ALL_JARS = {
            "katharsis_proxy-0.2.jar", "katharsis_paper-0.2-mc26.jar", "katharsis_paper-0.2.jar",
            "katharsis_neoforge-0.2-mc1.21.11.jar", "katharsis_neoforge-0.2.jar", "katharsis_neoforge-0.2-mc26.jar"
    };

    @TempDir
    Path dir;

    private FakeReleaseSystem rsys;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        rsys = new FakeReleaseSystem();
    }

    @AfterEach
    void stop() {
        rsys.close();
    }

    private Updater updater(String running, boolean official, String artifact, String classifier,
                            Channel channel, boolean autoDownload) {
        BuildInfo build = new BuildInfo(Version.parse(running), official, artifact, classifier);
        ReleaseClient client = new ReleaseClient(ReleaseClient.defaultHttpClient(), rsys.baseUrl() + "/", "test");
        return new Updater(build, client, new Updater.Settings(channel, autoDownload),
                file -> dir.resolve("updates").resolve(file.filename()), new Updater.Log() {
            @Override
            public void info(String message) {
                log.add("INFO " + message);
            }

            @Override
            public void warn(String message) {
                log.add("WARN " + message);
            }
        });
    }

    @Test
    @DisplayName("an official jar downloads the matching variant, verified, when one is newer")
    void downloadsTheMatchingVariant() throws IOException {
        rsys.publish(true, "0.2-dev.5", true, ALL_JARS);
        Updater updater = updater("0.2-dev.4", true, "katharsis_paper", "mc26", Channel.AUTO, true);

        Updater.Result result = updater.check();

        assertEquals(Updater.Status.DOWNLOADED, result.status());
        assertEquals("katharsis_paper-0.2-mc26.jar", result.file().filename());
        Path jar = dir.resolve("updates/katharsis_paper-0.2-mc26.jar");
        assertArrayEquals(rsys.files.get("katharsis_paper-0.2-mc26.jar"), Files.readAllBytes(jar));
        assertEquals(jar, updater.downloaded().orElseThrow().path());
        assertTrue(rsys.requests.contains("GET /katharsis/public/latest.json?prerelease=true&since=0.2-dev.4"),
                rsys.requests::toString);
        assertTrue(log.stream().anyMatch(line -> line.contains("0.2-dev.5 is available")
                && line.contains("2 releases behind")), log::toString);
    }

    @Test
    @DisplayName("the default variant is not confused with a classified one")
    void defaultVariant() {
        rsys.publish(false, "0.2", false, ALL_JARS);
        assertEquals("katharsis_neoforge-0.2.jar",
                updater("0.1", true, "katharsis_neoforge", "", Channel.AUTO, false).check().file().filename());
        assertEquals("katharsis_neoforge-0.2-mc1.21.11.jar",
                updater("0.1", true, "katharsis_neoforge", "mc1.21.11", Channel.AUTO, false).check().file().filename());
        assertEquals("katharsis_proxy-0.2.jar",
                updater("0.1", true, "katharsis_proxy", "", Channel.AUTO, false).check().file().filename());
    }

    @Test
    @DisplayName("a release jar only looks at releases, unless told to follow dev builds")
    void channels() {
        rsys.publish(false, "0.1", false, ALL_JARS);
        rsys.publish(true, "0.2-dev.3", true, ALL_JARS);

        assertEquals(Updater.Status.UP_TO_DATE,
                updater("0.1", true, "katharsis_proxy", "", Channel.AUTO, false).check().status());
        Updater.Result dev = updater("0.1", true, "katharsis_proxy", "", Channel.PRERELEASE, false).check();
        assertEquals(Updater.Status.AVAILABLE, dev.status());
        assertEquals(Version.parse("0.2-dev.3"), dev.latest().version());
    }

    @Test
    @DisplayName("nothing newer, or nothing released at all, is up to date")
    void upToDate() {
        assertEquals(Updater.Status.UP_TO_DATE,
                updater("0.1", true, "katharsis_proxy", "", Channel.RELEASE, true).check().status());
        rsys.publish(false, "0.1", false, ALL_JARS);
        assertEquals(Updater.Status.UP_TO_DATE,
                updater("0.1", true, "katharsis_proxy", "", Channel.RELEASE, true).check().status());
        assertEquals(Updater.Status.UP_TO_DATE,
                updater("0.2-dev.1", true, "katharsis_proxy", "", Channel.RELEASE, true).check().status());
    }

    @Test
    @DisplayName("a jar built by hand is told about updates but never replaced on its own")
    void localBuildsAreNotAutoUpdated() {
        rsys.publish(false, "0.2", false, ALL_JARS);
        Updater updater = updater("0.1", false, "katharsis_proxy", "", Channel.AUTO, true);

        assertEquals(Updater.Status.AVAILABLE, updater.check().status());
        assertTrue(updater.downloaded().isEmpty());
        assertFalse(Files.exists(dir.resolve("updates/katharsis_proxy-0.2.jar")));

        // The admin asking is another matter.
        assertEquals(Updater.Status.DOWNLOADED, updater.checkAndDownload().status());
        assertTrue(Files.exists(dir.resolve("updates/katharsis_proxy-0.2.jar")));
    }

    @Test
    @DisplayName("a download that does not match its sha256 is thrown away")
    void badChecksum() {
        rsys.advertisedSha.put("katharsis_proxy-0.2.jar", "00".repeat(32));
        rsys.publish(false, "0.2", false, ALL_JARS);
        Updater updater = updater("0.1", true, "katharsis_proxy", "", Channel.AUTO, true);

        Updater.Result result = updater.check();

        assertEquals(Updater.Status.FAILED, result.status());
        assertTrue(result.error().contains("sha256"), result.error());
        assertFalse(Files.exists(dir.resolve("updates/katharsis_proxy-0.2.jar")));
        assertFalse(Files.exists(dir.resolve("updates/katharsis_proxy-0.2.jar.part")));
        assertTrue(updater.downloaded().isEmpty());
    }

    @Test
    @DisplayName("a release without this variant says so instead of installing the wrong jar")
    void missingVariant() {
        rsys.publish(false, "0.2", false, "katharsis_proxy-0.2.jar");
        Updater.Result result = updater("0.1", true, "katharsis_paper", "mc26", Channel.AUTO, true).check();
        assertEquals(Updater.Status.NO_FILE, result.status());
    }

    @Test
    @DisplayName("an unreachable release system fails the check and is logged once")
    void unreachable() {
        rsys.close();
        Updater updater = updater("0.1", true, "katharsis_proxy", "", Channel.AUTO, true);
        assertEquals(Updater.Status.FAILED, updater.check().status());
        assertEquals(Updater.Status.FAILED, updater.check().status());
        assertEquals(1, log.stream().filter(line -> line.contains("Update check failed")).count(), log::toString);
    }

    @Test
    @DisplayName("an update is announced once, not at every check")
    void announcedOnce() {
        rsys.publish(false, "0.2", false, ALL_JARS);
        Updater updater = updater("0.1", true, "katharsis_proxy", "", Channel.AUTO, false);
        updater.check();
        updater.check();
        assertEquals(1, log.stream().filter(line -> line.contains("is available")).count(), log::toString);
    }

    @Test
    @DisplayName("once downloaded, later checks do not download it again")
    void downloadsOnce() {
        rsys.publish(false, "0.2", false, ALL_JARS);
        Updater updater = updater("0.1", true, "katharsis_proxy", "", Channel.AUTO, true);
        updater.check();
        assertEquals(Updater.Status.DOWNLOADED, updater.check().status());
        assertEquals(1, rsys.requests.stream().filter(line -> line.startsWith("GET /cdn/")).count());
    }
}
