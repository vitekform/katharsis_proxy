package me.ganamaga.katharsis_updater;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What this jar is, read from {@value #RESOURCE}, which the build writes:
 *
 * <pre>
 * version=0.2                 the project version, from the pom or gradle.properties
 * release=0.2-dev.41          the release system's name for it; CI fills this in
 * artifact=katharsis_paper    the jar's base name
 * classifier=mc26             which variant of it, empty for the default one
 * </pre>
 *
 * <p>Only CI knows the release: a jar built on someone's machine has none,
 * says the project version instead, and is {@linkplain #official() not
 * official}. Such a jar is told about updates but never replaced by one.</p>
 *
 * @param version    what to compare against the release system
 * @param official   whether CI built it, so it is exactly some published release
 * @param artifact   the jar's base name, e.g. {@code katharsis_proxy}
 * @param classifier the variant, e.g. {@code mc26}; empty for the default one
 */
public record BuildInfo(Version version, boolean official, String artifact, String classifier) {

    public static final String RESOURCE = "katharsis-build.properties";

    /**
     * Reads {@value #RESOURCE} through {@code owner}'s class loader.
     *
     * @throws IOException if the jar has no such file or it cannot be read
     */
    public static BuildInfo load(Class<?> owner) throws IOException {
        try (InputStream in = owner.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("the jar has no " + RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(in);
            return of(properties);
        }
    }

    public static BuildInfo of(Properties properties) {
        String release = filled(properties.getProperty("release"));
        String version = filled(properties.getProperty("version"));
        String artifact = filled(properties.getProperty("artifact"));
        String classifier = filled(properties.getProperty("classifier"));
        boolean official = !release.isEmpty();
        return new BuildInfo(Version.parse(official ? release : version), official, artifact, classifier);
    }

    /** A value the build did not fill in reads as its placeholder; treat that as missing. */
    private static String filled(String value) {
        if (value == null || value.contains("${")) {
            return "";
        }
        return value.strip();
    }
}
