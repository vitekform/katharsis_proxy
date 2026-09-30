package me.ganamaga.katharsis_updater;

import java.util.Locale;

/** Which releases count as updates. */
public enum Channel {

    /** Tagged releases only. */
    RELEASE,

    /** Every build of master as well as tagged releases. */
    PRERELEASE,

    /**
     * Whatever the running jar is: a dev build follows dev builds, a release
     * follows releases.
     */
    AUTO;

    /**
     * Reads a config value; {@code dev} and {@code prerelease} mean the same.
     * Anything unknown is {@link #AUTO}.
     */
    public static Channel parse(String value) {
        if (value == null) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "release", "stable" -> RELEASE;
            case "prerelease", "dev", "snapshot" -> PRERELEASE;
            default -> AUTO;
        };
    }

    /** Whether prereleases are wanted, for a jar of this version. */
    public boolean includesPrereleases(Version running) {
        return switch (this) {
            case RELEASE -> false;
            case PRERELEASE -> true;
            case AUTO -> running.isPrerelease();
        };
    }
}
