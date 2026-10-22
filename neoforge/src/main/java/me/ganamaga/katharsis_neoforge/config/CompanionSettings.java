package me.ganamaga.katharsis_neoforge.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * {@value #FILE_NAME} in the config folder: the settings the Paper companion
 * keeps in its config.yml. Free of Minecraft types so it can be tested without
 * a server.
 */
public final class CompanionSettings {

    public static final String FILE_NAME = "katharsis-companion.properties";

    private static final String NETWORK_FINGERPRINT = "network-fingerprint";

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

    /**
     * The fingerprint of the proxy's GNLib network key ({@code /kidentity} on
     * the proxy), as typed; blank when none is set. Only read with GNLib
     * installed.
     */
    public String networkFingerprint() {
        return properties.getProperty(NETWORK_FINGERPRINT, "").strip();
    }

    /**
     * Sets {@code network-fingerprint} in {@code file}, keeping every other
     * line and comment as it is: the line is replaced where it is, or added at
     * the end. Written to a temporary file first, so a crash leaves the old one.
     */
    public static void writeNetworkFingerprint(Path file, String fingerprint) throws IOException {
        List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
        String line = NETWORK_FINGERPRINT + "=" + fingerprint;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.startsWith(NETWORK_FINGERPRINT)
                    && trimmed.substring(NETWORK_FINGERPRINT.length()).strip().matches("[=:].*|")) {
                lines.set(i, line);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(line);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
