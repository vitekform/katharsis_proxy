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
 * <p>The companion ships one jar for Paper 1.21.1 through 26.2. That only works
 * because it is compiled against the oldest API in the range and emits bytecode
 * the oldest server can read. Both are easy to break by "upgrading" a version
 * property, and neither would fail any other test — so they are asserted
 * here.</p>
 */
class PluginDescriptorTest {

    /** Class file major version for Java 21, the floor of the supported range. */
    private static final int JAVA_21_CLASS_FILE = 65;

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
    @DisplayName("the jar is Java 21 bytecode, so the oldest supported server can load it")
    void bytecodeTargetsTheOldestSupportedServer() throws IOException {
        try (InputStream in = PluginDescriptorTest.class.getClassLoader()
                .getResourceAsStream("me/ganamaga/katharsis_paper/KatharsisPaper.class")) {
            assertNotNull(in, "the plugin class should be on the test classpath");
            byte[] header = in.readNBytes(8);

            int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            assertEquals(JAVA_21_CLASS_FILE, major,
                    "compiling against a newer Paper API raises the bytecode level and stops "
                            + "1.21.x servers loading the plugin at all");
        }
    }

    @Test
    @DisplayName("api-version declares the oldest supported server, not the newest")
    void apiVersionDeclaresTheMinimum() throws IOException {
        String declared = pluginYml("api-version");
        String minimum = property("katharsis.paper.supported.min");

        assertEquals(property("katharsis.paper.api.version"), declared, "plugin.yml tracks the pom");
        assertTrue(minimum.startsWith(declared + "."),
                () -> "api-version " + declared + " should be the line of the minimum supported "
                        + minimum + ", since that is the oldest server this jar targets");
    }

    @Test
    @DisplayName("the API compiled against is the oldest supported one")
    void compiledAgainstTheOldestApi() {
        String compiledAgainst = property("katharsis.paper.version");
        String minimum = property("katharsis.paper.supported.min");

        assertTrue(compiledAgainst.startsWith(minimum),
                () -> "built against " + compiledAgainst + " but claiming to support " + minimum
                        + " and up; a jar cannot run on a server older than the API it was built on");
        assertEquals("21", property("katharsis.java.release"),
                "the 1.21.x line runs on Java 21, so that is the target");
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
