package me.ganamaga.katharsis_paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the things that decide whether the plugin loads at all, and on which
 * servers.
 *
 * <p>The companion ships one jar per Paper line, each compiled against the
 * oldest API in its line so it runs on every release of that line. The build
 * variant is chosen by a Maven profile, and these tests check the variant being
 * built is internally consistent: that the bytecode matches the JDK its line
 * needs, that the API compiled against really is the oldest one claimed, and
 * that {@code api-version} names that same minimum.</p>
 *
 * <p>All three are easy to break by "upgrading" a version property, and none
 * would fail any other test.</p>
 */
class PluginDescriptorTest {

    /** A class file's major version is its Java release plus this. */
    private static final int CLASS_FILE_OFFSET = 44;

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, () -> name + " is not set; run the tests through Maven");
        return value;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = PluginDescriptorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, () -> name + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String pluginYml(String key) throws IOException {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*'?([^'\\n]+?)'?\\s*$")
                .matcher(resource("plugin.yml"));
        assertTrue(matcher.find(), () -> "plugin.yml has no '" + key + "' key");
        return matcher.group(1);
    }

    @Test
    @DisplayName("the bytecode matches the Java release this variant's servers run")
    void bytecodeMatchesTheTargetedRelease() throws IOException {
        int release = Integer.parseInt(property("katharsis.java.release"));

        try (InputStream in = PluginDescriptorTest.class.getClassLoader()
                .getResourceAsStream("me/ganamaga/katharsis_paper/KatharsisPaper.class")) {
            assertNotNull(in, "the plugin class should be on the test classpath");
            byte[] header = in.readNBytes(8);

            int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            assertEquals(release + CLASS_FILE_OFFSET, major,
                    () -> "the " + property("katharsis.paper.variant") + " variant must emit Java "
                            + release + " bytecode; anything newer stops its oldest servers loading "
                            + "the plugin at all");
        }
    }

    @Test
    @DisplayName("api-version declares the oldest supported server, not the newest")
    void apiVersionDeclaresTheMinimum() throws IOException {
        String declared = pluginYml("api-version");
        String minimum = property("katharsis.paper.supported.min");

        assertEquals(property("katharsis.paper.api.version"), declared, "plugin.yml tracks the pom");
        assertTrue(minimum.equals(declared) || minimum.startsWith(declared + "."),
                () -> "api-version " + declared + " should name the minimum supported server "
                        + minimum + ", or the line it belongs to");
    }

    @Test
    @DisplayName("the API compiled against is the oldest this variant claims to support")
    void compiledAgainstTheOldestApi() {
        String compiledAgainst = property("katharsis.paper.version");
        String minimum = property("katharsis.paper.supported.min");

        assertTrue(compiledAgainst.startsWith(minimum),
                () -> "built against " + compiledAgainst + " but claiming to support " + minimum
                        + " and up; a jar cannot run on a server older than the API it was built on");
    }

    @Test
    @DisplayName("the two variants stay distinguishable on disk")
    void variantsAreNamedApart() {
        String variant = property("katharsis.paper.variant");

        assertTrue(variant.equals("1.21.x") || variant.equals("26.x"),
                () -> "unexpected variant: " + variant);
        assertEquals("1.21.x".equals(variant) ? "21" : "25", property("katharsis.java.release"),
                "the 1.21.x line runs on Java 21 and the 26.x line on Java 25");
    }

    @Test
    @DisplayName("the main class named in plugin.yml exists and is the plugin")
    void mainClassExists() throws IOException {
        String main = pluginYml("main");

        assertEquals(KatharsisPaper.class.getName(), main);
        assertDoesNotThrow(() -> Class.forName(main), "the server must be able to load it");
    }

    @Test
    @DisplayName("Maven filled the version in, so the server does not read a placeholder")
    void versionIsFiltered() throws IOException {
        String version = pluginYml("version");

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
