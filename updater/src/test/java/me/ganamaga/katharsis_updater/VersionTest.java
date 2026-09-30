package me.ganamaga.katharsis_updater;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    private static Version v(String text) {
        return Version.parse(text);
    }

    @Test
    @DisplayName("a dev build comes before its release, which comes before the next dev build")
    void devBuildsSortAroundReleases() {
        List<String> ordered = List.of("0.1-dev.2", "0.1-dev.13", "0.1", "0.1.1", "0.2-dev.1", "0.2-dev.14",
                "0.2", "0.10", "1.0-dev.1", "1.0");
        List<Version> shuffled = new ArrayList<>(ordered.stream().map(Version::parse).toList());
        Collections.shuffle(shuffled, new java.util.Random(7));
        Collections.sort(shuffled);
        assertEquals(ordered, shuffled.stream().map(Version::toString).toList());
    }

    @Test
    @DisplayName("dev numbers compare as numbers, not text")
    void buildNumbersAreNumeric() {
        assertTrue(v("0.1-dev.10").isNewerThan(v("0.1-dev.9")));
        assertTrue(v("0.1-dev.100").isNewerThan(v("0.1-dev.99")));
    }

    @Test
    @DisplayName("trailing zeros, leading zeros and a v prefix do not matter")
    void equivalentSpellings() {
        assertEquals(v("0.2"), v("0.2.0"));
        assertEquals(v("0.2").hashCode(), v("0.2.0").hashCode());
        assertEquals(v("0.2"), v("v0.2"));
        assertEquals(v("0.2-dev.05"), v("0.2-dev.5"));
        assertEquals(v("0.2-dev.05").hashCode(), v("0.2-dev.5").hashCode());
        assertFalse(v("0.2").isNewerThan(v("0.2.0")));
    }

    @Test
    @DisplayName("build metadata is ignored and huge numbers do not overflow")
    void oddInput() {
        assertEquals(v("0.2"), v("0.2+abc"));
        assertTrue(v("99999999999999999999999").isNewerThan(v("99999999999999999999998")));
        assertTrue(v("").compareTo(v("0.1")) < 0);
        assertTrue(v("garbage").isNewerThan(v("0.1")));
    }

    @Test
    void prereleaseFlag() {
        assertTrue(v("0.1-dev.13").isPrerelease());
        assertFalse(v("0.1").isPrerelease());
    }
}
