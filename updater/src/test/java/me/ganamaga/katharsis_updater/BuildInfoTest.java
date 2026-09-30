package me.ganamaga.katharsis_updater;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildInfoTest {

    private static Properties props(String release, String classifier) {
        Properties properties = new Properties();
        properties.setProperty("version", "0.2");
        properties.setProperty("release", release);
        properties.setProperty("artifact", "katharsis_paper");
        properties.setProperty("classifier", classifier);
        return properties;
    }

    @Test
    void ciBuildsCarryTheirReleaseName() {
        BuildInfo build = BuildInfo.of(props("0.2-dev.41", "mc26"));
        assertTrue(build.official());
        assertEquals(Version.parse("0.2-dev.41"), build.version());
        assertEquals("katharsis_paper", build.artifact());
        assertEquals("mc26", build.classifier());
    }

    @Test
    void localBuildsFallBackToTheProjectVersion() {
        BuildInfo build = BuildInfo.of(props("", ""));
        assertFalse(build.official());
        assertEquals(Version.parse("0.2"), build.version());
        assertEquals("", build.classifier());
    }

    @Test
    void anUnfilledPlaceholderCountsAsMissing() {
        BuildInfo build = BuildInfo.of(props("${katharsis.release}", "${classifier}"));
        assertFalse(build.official());
        assertEquals("", build.classifier());
    }
}
