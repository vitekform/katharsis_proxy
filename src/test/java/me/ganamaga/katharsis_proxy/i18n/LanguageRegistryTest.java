package me.ganamaga.katharsis_proxy.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageRegistryTest {

    private LanguageRegistry loaded(Path directory, String defaultLanguage) throws ConfigurateException {
        LanguageRegistry registry =
                new LanguageRegistry(LoggerFactory.getLogger(LanguageRegistryTest.class), directory);
        registry.load(defaultLanguage);
        return registry;
    }

    @Test
    @DisplayName("writes the bundled languages out and loads them")
    void writesAndLoadsBundledLanguages(@TempDir Path directory) throws ConfigurateException {
        LanguageRegistry registry = loaded(directory, "en_US");

        assertEquals(List.of("cs_CZ", "en_US"), List.copyOf(registry.languages()));
        assertTrue(Files.exists(directory.resolve("lang/en_US.yml")));
        assertTrue(Files.exists(directory.resolve("lang/cs_CZ.yml")));
        assertTrue(registry.isKnown("cs_CZ"));
        assertFalse(registry.isKnown("zz_ZZ"));
    }

    @Test
    @DisplayName("every key is translated in every bundled language")
    void bundledLanguagesAreComplete(@TempDir Path directory) throws ConfigurateException {
        LanguageRegistry registry = loaded(directory, "en_US");

        for (String language : registry.languages()) {
            for (MessageKey key : MessageKey.values()) {
                assertFalse(registry.template(language, key).equals(key.key()),
                        () -> language + " is missing '" + key.key() + "'");
            }
        }
    }

    @Test
    @DisplayName("an extra file in lang/ becomes a language")
    void extraFileIsPickedUp(@TempDir Path directory) throws ConfigurateException, IOException {
        loaded(directory, "en_US");
        Files.writeString(directory.resolve("lang/de_DE.yml"), "connecting: \"Verbinde zu <server>...\"\n");

        LanguageRegistry registry = loaded(directory, "en_US");

        assertTrue(registry.isKnown("de_DE"));
        assertEquals("Verbinde zu <server>...", registry.template("de_DE", MessageKey.CONNECTING));
    }

    @Test
    @DisplayName("a key missing from one language falls back to another")
    void missingKeyFallsBack(@TempDir Path directory) throws ConfigurateException, IOException {
        loaded(directory, "en_US");
        Files.writeString(directory.resolve("lang/de_DE.yml"), "connecting: \"Verbinde zu <server>...\"\n");
        LanguageRegistry registry = loaded(directory, "en_US");

        assertEquals(registry.template("en_US", MessageKey.PLAYERS_ONLY),
                registry.template("de_DE", MessageKey.PLAYERS_ONLY));
    }

    @Test
    @DisplayName("an unknown language falls back to the default")
    void unknownLanguageFallsBack(@TempDir Path directory) throws ConfigurateException {
        LanguageRegistry registry = loaded(directory, "cs_CZ");

        assertEquals("cs_CZ", registry.defaultLanguage());
        assertEquals(registry.template("cs_CZ", MessageKey.CONNECTING),
                registry.template("zz_ZZ", MessageKey.CONNECTING));
    }

    @Test
    @DisplayName("a default-language with no file falls back to en_US")
    void unknownDefaultFallsBackToRoot(@TempDir Path directory) throws ConfigurateException {
        assertEquals(LanguageRegistry.ROOT_LANGUAGE, loaded(directory, "zz_ZZ").defaultLanguage());
    }

    @Test
    @DisplayName("a deleted language file is restored from the jar")
    void deletedFileIsRestored(@TempDir Path directory) throws ConfigurateException, IOException {
        loaded(directory, "en_US");
        Files.delete(directory.resolve("lang/en_US.yml"));

        LanguageRegistry registry = loaded(directory, "en_US");

        assertTrue(Files.exists(directory.resolve("lang/en_US.yml")), "rewritten from the jar");
        assertTrue(registry.isKnown("en_US"));
    }

    @ParameterizedTest
    @DisplayName("language codes are normalised to the file naming")
    @CsvSource({"EN-us,en_US", "en-US,en_US", "cs_cz,cs_CZ", "CS-CZ,cs_CZ", "en_US,en_US", "FR,fr", "  en-us  ,en_US"})
    void normalisesCodes(String input, String expected) {
        assertEquals(expected, LanguageRegistry.normalise(input));
    }

    @Test
    @DisplayName("a file named longer than the column is ignored")
    void overlongFileNameIgnored(@TempDir Path directory) throws ConfigurateException, IOException {
        loaded(directory, "en_US");
        Files.writeString(directory.resolve("lang/en_US_LONG.yml"), "connecting: \"x\"\n");

        assertFalse(loaded(directory, "en_US").isKnown("en_US_LONG"));
    }

    @Test
    @DisplayName("an unreadable language file surfaces as a load failure")
    void brokenYamlFails(@TempDir Path directory) throws ConfigurateException, IOException {
        loaded(directory, "en_US");
        Files.writeString(directory.resolve("lang/de_DE.yml"), "\tnot: [valid\n  yaml\n");

        assertThrows(ConfigurateException.class, () -> loaded(directory, "en_US"));
    }
}
