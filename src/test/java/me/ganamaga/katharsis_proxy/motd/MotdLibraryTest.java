package me.ganamaga.katharsis_proxy.motd;

import me.ganamaga.katharsis_proxy.config.MotdMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotdLibraryTest {

    private MotdLibrary libraryIn(Path directory) {
        return new MotdLibrary(LoggerFactory.getLogger(MotdLibraryTest.class), directory);
    }

    /** Writes a banner whose plain text is {@code text}, as the generators emit. */
    private void writeBanner(Path directory, String fileName, String text) throws IOException {
        Files.writeString(directory.resolve(fileName), "{\"text\":\"" + text + "\"}");
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String selected(MotdLibrary library, MotdMode mode) {
        return plain(library.select(mode).orElseThrow());
    }

    @Test
    @DisplayName("the first banner has no number, the rest are numbered from two")
    void readsNumberedFiles(@TempDir Path directory) throws IOException {
        writeBanner(directory, "motd.json", "first");
        writeBanner(directory, "motd2.json", "second");
        writeBanner(directory, "motd3.json", "third");

        MotdLibrary library = libraryIn(directory);
        library.load(3);

        assertEquals(3, library.size());
        assertFalse(library.isEmpty());
    }

    @Test
    @DisplayName("a file that is missing is skipped, not fatal")
    void skipsMissingFiles(@TempDir Path directory) throws IOException {
        writeBanner(directory, "motd.json", "first");
        // motd2.json is deliberately absent.
        writeBanner(directory, "motd3.json", "third");

        MotdLibrary library = libraryIn(directory);
        library.load(3);

        assertEquals(2, library.size());
    }

    @Test
    @DisplayName("a malformed banner is skipped so one bad file cannot break the ping")
    void skipsMalformedFiles(@TempDir Path directory) throws IOException {
        writeBanner(directory, "motd.json", "good");
        Files.writeString(directory.resolve("motd2.json"), "{this is not json");

        MotdLibrary library = libraryIn(directory);
        library.load(2);

        assertEquals(1, library.size());
        assertEquals("good", selected(library, MotdMode.RANDOM));
    }

    @Test
    @DisplayName("nothing loaded means nothing selected, and the caller falls back")
    void emptyWhenNothingLoads(@TempDir Path directory) {
        MotdLibrary library = libraryIn(directory);
        library.load(2);

        assertTrue(library.isEmpty());
        assertTrue(library.select(MotdMode.RANDOM).isEmpty());
        assertTrue(library.select(MotdMode.ROLLING).isEmpty());
    }

    @Test
    @DisplayName("rolling serves one banner at a time and wraps around")
    void rollingAdvancesInOrder(@TempDir Path directory) throws IOException {
        writeBanner(directory, "motd.json", "first");
        writeBanner(directory, "motd2.json", "second");

        MotdLibrary library = libraryIn(directory);
        library.load(2);

        assertEquals("first", selected(library, MotdMode.ROLLING));
        assertEquals("first", selected(library, MotdMode.ROLLING), "stable until it is advanced");

        library.advance();
        assertEquals("second", selected(library, MotdMode.ROLLING));

        library.advance();
        assertEquals("first", selected(library, MotdMode.ROLLING), "wraps");
    }

    @Test
    @DisplayName("random stays within the loaded set and does eventually vary")
    void randomStaysInRange(@TempDir Path directory) throws IOException {
        writeBanner(directory, "motd.json", "first");
        writeBanner(directory, "motd2.json", "second");

        MotdLibrary library = libraryIn(directory);
        library.load(2);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(selected(library, MotdMode.RANDOM));
        }

        assertEquals(Set.of("first", "second"), seen);
    }

    @Test
    @DisplayName("reloading replaces the set and restarts the rotation")
    void reloadReplacesTheSet(@TempDir Path directory) throws IOException {
        writeBanner(directory, "motd.json", "first");
        writeBanner(directory, "motd2.json", "second");

        MotdLibrary library = libraryIn(directory);
        library.load(2);
        library.advance();
        assertEquals("second", selected(library, MotdMode.ROLLING));

        writeBanner(directory, "motd.json", "replaced");
        library.load(1);

        assertEquals(1, library.size());
        assertEquals("replaced", selected(library, MotdMode.ROLLING), "the rotation starts over");
    }
}
