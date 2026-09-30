package me.ganamaga.katharsis_neoforge.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * {@value #FILE_NAME} in the config folder. Kept apart from {@link ModUpdates}
 * so it can be tested without Minecraft on the classpath.
 */
final class UpdateSettings {

    static final String FILE_NAME = "katharsis-updates.properties";

    private static final long DEFAULT_HOURS = 6L;

    private final Properties properties;

    private UpdateSettings(Properties properties) {
        this.properties = properties;
    }

    static UpdateSettings defaults() {
        return new UpdateSettings(new Properties());
    }

    /**
     * Reads the file, first writing the documented default bundled in the jar
     * if there is none.
     */
    static UpdateSettings load(Path file) throws IOException {
        if (Files.notExists(file)) {
            try (InputStream defaults = UpdateSettings.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
                if (defaults != null) {
                    Files.createDirectories(file.toAbsolutePath().getParent());
                    Files.copy(defaults, file);
                }
            }
        }
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return new UpdateSettings(properties);
    }

    boolean enabled() {
        return flag("enabled");
    }

    String channel() {
        return properties.getProperty("channel", "auto").strip();
    }

    boolean autoDownload() {
        return flag("auto-download");
    }

    /** Hours between checks; 0 checks only at startup. */
    long checkIntervalHours() {
        try {
            return Math.max(0L, Long.parseLong(properties.getProperty("check-interval-hours", "").strip()));
        } catch (NumberFormatException e) {
            return DEFAULT_HOURS;
        }
    }

    String url() {
        return properties.getProperty("url", "").strip();
    }

    /** On unless it says false. */
    private boolean flag(String key) {
        return !"false".equalsIgnoreCase(properties.getProperty(key, "true").strip());
    }
}
