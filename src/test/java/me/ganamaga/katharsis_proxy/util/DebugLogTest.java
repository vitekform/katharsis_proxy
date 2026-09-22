package me.ganamaga.katharsis_proxy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers what is written, how it is stamped, and that nothing here throws. */
class DebugLogTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugLogTest.class);

    @TempDir
    Path dataDirectory;

    /** A fixed clock, so the stamp can be asserted rather than only matched. */
    private DebugLog fixed(Path directory) {
        return new DebugLog(LOGGER, directory.resolve(DebugLog.FILE_NAME),
                Clock.fixed(Instant.parse("2026-09-22T18:04:05.678Z"), ZoneOffset.UTC));
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(dataDirectory.resolve(DebugLog.FILE_NAME), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("stamps each line and substitutes the arguments")
    void writesAStampedLine() throws IOException {
        fixed(dataDirectory).log("openai call={} status={}", 7, 200);

        assertEquals(List.of("2026-09-22 18:04:05.678 openai call=7 status=200"), lines());
    }

    @Test
    @DisplayName("appends rather than replacing what is already there")
    void appends() throws IOException {
        DebugLog log = fixed(dataDirectory);
        log.log("first");
        log.log("second");

        assertEquals(2, lines().size());
        assertTrue(lines().get(1).endsWith("second"), lines().get(1));
    }

    @Test
    @DisplayName("keeps a multi-line argument on one line")
    void escapesNewlines() throws IOException {
        fixed(dataDirectory).log("text={}", "one\ntwo\r\nthree");

        assertEquals(List.of("2026-09-22 18:04:05.678 text=one\\ntwo\\r\\nthree"), lines());
    }

    @Test
    @DisplayName("leaves a placeholder alone when there is no argument for it")
    void keepsUnmatchedPlaceholders() {
        assertEquals("a=1 b={}", DebugLog.format("a={} b={}", 1));
        assertEquals("a=1", DebugLog.format("a={}", 1, "ignored"));
        assertEquals("nothing to fill", DebugLog.format("nothing to fill"));
        assertEquals("null", DebugLog.format("{}", (Object) null));
    }

    @Test
    @DisplayName("creates the directory and the file, and says it is on")
    void createsTheFile() {
        Path directory = dataDirectory.resolve("not/there/yet");
        DebugLog log = DebugLog.create(LOGGER, directory, true);

        assertTrue(log.isEnabled());
        assertEquals(directory.resolve(DebugLog.FILE_NAME), log.path());
        assertTrue(Files.exists(directory.resolve(DebugLog.FILE_NAME)));
    }

    @Test
    @DisplayName("writes nothing, and no file, when it is off")
    void offWritesNothing() {
        DebugLog log = DebugLog.create(LOGGER, dataDirectory, false);
        log.log("openai call={}", 1);

        assertSame(DebugLog.DISABLED, log);
        assertFalse(log.isEnabled());
        assertFalse(Files.exists(dataDirectory.resolve(DebugLog.FILE_NAME)));
    }

    @Test
    @DisplayName("keeps writing after the file is deleted underneath it")
    void survivesTheFileBeingDeleted() throws IOException {
        DebugLog log = fixed(dataDirectory);
        log.log("before");
        Files.delete(dataDirectory.resolve(DebugLog.FILE_NAME));
        log.log("after");

        assertEquals(1, lines().size());
        assertTrue(lines().get(0).endsWith("after"), lines().get(0));
    }

    @Test
    @DisplayName("rotates the file once it is full, keeping the previous one")
    void rotatesWhenFull() throws IOException {
        Path path = dataDirectory.resolve(DebugLog.FILE_NAME);
        Files.writeString(path, "x".repeat(5 * 1024 * 1024 + 1), StandardCharsets.UTF_8);

        fixed(dataDirectory).log("after the rotation");

        assertEquals(List.of("2026-09-22 18:04:05.678 after the rotation"), lines());
        assertTrue(Files.exists(dataDirectory.resolve(DebugLog.FILE_NAME + ".1")));
        assertEquals(5 * 1024 * 1024 + 1, Files.size(dataDirectory.resolve(DebugLog.FILE_NAME + ".1")));
    }

    @Test
    @DisplayName("goes quiet instead of throwing when the file cannot be written")
    void neverThrows() throws IOException {
        // A directory where the file should be, so every write fails.
        Path path = dataDirectory.resolve(DebugLog.FILE_NAME);
        Files.createDirectory(path);
        DebugLog log = new DebugLog(LOGGER, path, Clock.systemUTC());

        log.log("this one cannot be written");
        log.log("and neither can this one");

        assertTrue(Files.isDirectory(path));
    }
}
