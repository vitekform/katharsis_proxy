package me.ganamaga.katharsis_paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the descriptor the server reads before any of our code runs.
 *
 * <p>A mistake here — a stale api-version, a renamed main class — stops the
 * plugin from loading at all, and no other test would notice.</p>
 */
class PluginDescriptorTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = PluginDescriptorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, () -> name + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String value(String yaml, String key) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*'?([^'\\n]+?)'?\\s*$")
                .matcher(yaml);
        assertTrue(matcher.find(), () -> "plugin.yml has no '" + key + "' key");
        return matcher.group(1);
    }

    @Test
    @DisplayName("api-version matches the Paper API actually compiled against")
    void apiVersionMatchesTheApiJar() throws IOException {
        String declared = value(resource("plugin.yml"), "api-version");

        // Shipped inside paper-api itself, so this tracks the dependency.
        Matcher current = Pattern.compile("\"currentApiVersion\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(resource("apiVersioning.json"));
        assertTrue(current.find(), "paper-api should ship apiVersioning.json");

        assertEquals(current.group(1), declared,
                "plugin.yml's api-version must track the paper-api version in the pom");
    }

    @Test
    @DisplayName("the main class named in plugin.yml exists and is the plugin")
    void mainClassExists() throws IOException {
        String main = value(resource("plugin.yml"), "main");

        assertEquals(KatharsisPaper.class.getName(), main);
        assertDoesNotThrow(() -> Class.forName(main), "the server must be able to load it");
    }

    @Test
    @DisplayName("Maven filled the version in, so the server does not read a placeholder")
    void versionIsFiltered() throws IOException {
        String version = value(resource("plugin.yml"), "version");

        assertFalse(version.contains("${"), () -> "unfiltered placeholder in plugin.yml: " + version);
        assertTrue(version.matches("\\d+\\.\\d+.*"), () -> "unexpected version: " + version);
    }

    @Test
    @DisplayName("/language and its alias are declared, since the command is registered in code")
    void languageCommandIsDeclared() throws IOException {
        String yaml = resource("plugin.yml");

        assertTrue(yaml.contains("\n  language:"), "plugin.yml must declare the language command");
        assertTrue(yaml.contains("aliases: [lang]"), "the /lang alias replaces the proxy's");
    }
}
