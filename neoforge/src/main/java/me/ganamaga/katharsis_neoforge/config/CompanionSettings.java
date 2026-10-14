package me.ganamaga.katharsis_neoforge.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * {@value #FILE_NAME} in the config folder: the settings the Paper companion
 * keeps in its config.yml. Free of Minecraft types so it can be tested without
 * a server.
 */
public final class CompanionSettings {

    public static final String FILE_NAME = "katharsis-companion.properties";

    private static final long DEFAULT_TIMEOUT_SECONDS = 5L;
    private static final String DEFAULT_PROXY_UNREACHABLE =
            "<red>Could not reach the proxy. Please try again in a moment.";
    private static final String DEFAULT_PLAYERS_ONLY = "<red>Only players can use this command.";

    private final Properties properties;

    private CompanionSettings(Properties properties) {
        this.properties = properties;
    }

    public static CompanionSettings defaults() {
        return new CompanionSettings(new Properties());
    }

    /**
     * Reads the file, first writing the documented default bundled in the jar
     * if there is none.
     */
    public static CompanionSettings load(Path file) throws IOException {
        if (Files.notExists(file)) {
            try (InputStream defaults = CompanionSettings.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
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
        return new CompanionSettings(properties);
    }

    /** Sent with chat reports and announcements; blank means this server's velocity.toml name. */
    public String serverId() {
        return properties.getProperty("server-id", "").strip();
    }

    /** At least a second, so a typo cannot fail every request at once. */
    public long requestTimeoutSeconds() {
        try {
            return Math.max(1L, Long.parseLong(properties.getProperty("request-timeout-seconds", "").strip()));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** A MiniMessage template. */
    public String proxyUnreachable() {
        return text("proxy-unreachable", DEFAULT_PROXY_UNREACHABLE);
    }

    /** A MiniMessage template. */
    public String playersOnly() {
        return text("players-only", DEFAULT_PLAYERS_ONLY);
    }

    private String text(String key, String fallback) {
        String value = properties.getProperty(key, "").strip();
        return value.isEmpty() ? fallback : value;
    }
}
