package me.ganamaga.katharsis_updater;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseTest {

    /** Trimmed from what rsys.ganamaga.me really answered for 0.1-dev.13. */
    private static final String LIVE = """
            {"project":"katharsis","name":"katharsis","description":null,"version":"0.1-dev.13",
             "title":"Katharsis 0.1-dev.13","changelog":"Built from `b5073ef1e029` on master.",
             "prerelease":true,"publishedAt":"2026-09-30T10:43:59.005356Z",
             "url":"https://rsys.ganamaga.me/katharsis/public/latest",
             "primary":{"filename":"katharsis_proxy-0.1.jar","size":31341493,"contentType":"application/java-archive",
               "sha256":"1E1B9BEB418BD458AC4EDA04068AC42DB72BE61297870E8D99EF3F38E10BCDE2","primary":true,
               "url":"https://rsys.ganamaga.me/katharsis/public/v/0.1-dev.13/katharsis_proxy-0.1.jar"},
             "files":[
               {"filename":"katharsis_proxy-0.1.jar","size":31341493,"contentType":"application/java-archive",
                "sha256":"1E1B9BEB418BD458AC4EDA04068AC42DB72BE61297870E8D99EF3F38E10BCDE2","primary":true,
                "url":"https://rsys.ganamaga.me/katharsis/public/v/0.1-dev.13/katharsis_proxy-0.1.jar"},
               {"filename":"katharsis_paper-0.1-mc26.jar","size":61394,"contentType":"application/java-archive",
                "sha256":"f5bf","primary":false,"url":"u1"},
               {"filename":"katharsis_neoforge-0.1-mc1.21.11.jar","size":14114,"contentType":"application/java-archive",
                "sha256":"3e9e","primary":false,"url":"u2"},
               {"filename":"katharsis_paper-0.1.jar","size":61404,"contentType":"application/java-archive",
                "sha256":"bfc8","primary":false,"url":"u3"}],
             "releases":[{"version":"0.1-dev.13","title":"Katharsis 0.1-dev.13","changelog":"x","prerelease":true,
               "publishedAt":"2026-09-30T10:43:59.005356Z"}]}
            """;

    @Test
    void parsesWhatTheReleaseSystemSends() throws IOException {
        Release release = ReleaseClient.parseLatest(LIVE);
        assertEquals(Version.parse("0.1-dev.13"), release.version());
        assertTrue(release.prerelease());
        assertEquals(4, release.files().size());
        assertEquals(List.of("0.1-dev.13"), release.history());
        ReleaseFile proxy = release.files().getFirst();
        assertEquals(31341493L, proxy.size());
        assertEquals("1e1b9beb418bd458ac4eda04068ac42db72be61297870e8d99ef3f38e10bcde2", proxy.sha256(),
                "hex is compared in lower case");
    }

    @Test
    void picksEachVariant() throws IOException {
        Release release = ReleaseClient.parseLatest(LIVE);
        assertEquals("katharsis_paper-0.1.jar", pick(release, "katharsis_paper", ""));
        assertEquals("katharsis_paper-0.1-mc26.jar", pick(release, "katharsis_paper", "mc26"));
        assertEquals("katharsis_neoforge-0.1-mc1.21.11.jar", pick(release, "katharsis_neoforge", "mc1.21.11"));
        assertFalse(release.fileFor(new BuildInfo(Version.parse("0"), true, "katharsis_neoforge", "")).isPresent());
        assertFalse(release.fileFor(new BuildInfo(Version.parse("0"), true, "katharsis", "")).isPresent(),
                "a prefix of another artifact's name is not a match");
    }

    @Test
    void classifiers() {
        assertEquals("", Release.classifierOf("0.1"));
        assertEquals("", Release.classifierOf("0.1-dev.13"));
        assertEquals("mc26", Release.classifierOf("0.1-mc26"));
        assertEquals("mc1.21.11", Release.classifierOf("0.1-mc1.21.11"));
    }

    @Test
    void rejectsSomethingElse() {
        assertThrows(IOException.class, () -> ReleaseClient.parseLatest("[]"));
        assertThrows(IOException.class, () -> ReleaseClient.parseLatest("{\"files\":[]}"));
        assertThrows(IOException.class, () -> ReleaseClient.parseLatest("not json"));
    }

    @Test
    void unsafeNames() {
        assertTrue(ReleaseClient.isSafeFilename("katharsis_proxy-0.1.jar"));
        assertFalse(ReleaseClient.isSafeFilename("../katharsis_proxy-0.1.jar"));
        assertFalse(ReleaseClient.isSafeFilename("a/b.jar"));
        assertFalse(ReleaseClient.isSafeFilename(".hidden.jar"));
        assertFalse(ReleaseClient.isSafeFilename("katharsis.zip"));
    }

    private static String pick(Release release, String artifact, String classifier) {
        return release.fileFor(new BuildInfo(Version.parse("0"), true, artifact, classifier))
                .orElseThrow().filename();
    }
}
