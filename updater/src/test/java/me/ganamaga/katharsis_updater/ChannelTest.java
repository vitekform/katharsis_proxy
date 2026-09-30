package me.ganamaga.katharsis_updater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelTest {

    @Test
    void parsesConfigValues() {
        assertEquals(Channel.RELEASE, Channel.parse(" Release "));
        assertEquals(Channel.PRERELEASE, Channel.parse("dev"));
        assertEquals(Channel.PRERELEASE, Channel.parse("prerelease"));
        assertEquals(Channel.AUTO, Channel.parse("auto"));
        assertEquals(Channel.AUTO, Channel.parse("nonsense"));
        assertEquals(Channel.AUTO, Channel.parse(null));
    }

    @Test
    void autoFollowsTheRunningJar() {
        assertTrue(Channel.AUTO.includesPrereleases(Version.parse("0.1-dev.3")));
        assertFalse(Channel.AUTO.includesPrereleases(Version.parse("0.1")));
        assertTrue(Channel.PRERELEASE.includesPrereleases(Version.parse("0.1")));
        assertFalse(Channel.RELEASE.includesPrereleases(Version.parse("0.1-dev.3")));
    }
}
