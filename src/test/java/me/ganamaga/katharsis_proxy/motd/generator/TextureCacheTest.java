package me.ganamaga.katharsis_proxy.motd.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureCacheTest {

    @Test
    @DisplayName("a missing file starts an empty cache without creating one")
    void missingFileIsEmpty(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("cache.json");

        TextureCache cache = TextureCache.load(file);

        assertEquals(0, cache.size());
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("every put is written straight away and reads back")
    void putPersists(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("cache.json");

        TextureCache.load(file).put("abc", "dmFsdWU=");

        assertEquals("{\"abc\":\"dmFsdWU=\"}", Files.readString(file), "compact and unescaped");
        assertEquals(Optional.of("dmFsdWU="), TextureCache.load(file).get("abc"));
    }

    @Test
    @DisplayName("reads the cache file the standalone script writes")
    void readsTheScriptsCache(@TempDir Path directory) throws IOException {
        // json.dump(cache, f) with default separators.
        Path file = Files.writeString(directory.resolve("skin_cache.json"),
                "{\"61a295277175bd49a11fcd6b700c73fa\": \"eyJhIjoxfQ==\", \"57bd\": \"eyJiIjoyfQ==\"}");

        TextureCache cache = TextureCache.load(file);

        assertEquals(2, cache.size());
        assertEquals(Optional.of("eyJhIjoxfQ=="), cache.get("61a295277175bd49a11fcd6b700c73fa"));
    }

    @Test
    @DisplayName("a corrupt cache fails loudly instead of being overwritten")
    void corruptCacheIsNotReplaced(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("cache.json"), "{\"abc\": [1, 2");

        assertThrows(IOException.class, () -> TextureCache.load(file));
        assertEquals("{\"abc\": [1, 2", Files.readString(file));
    }

    @Test
    @DisplayName("a cache that is not an object of strings is rejected")
    void wrongShapeIsRejected(@TempDir Path directory) throws IOException {
        assertThrows(IOException.class, () -> TextureCache.load(
                Files.writeString(directory.resolve("a.json"), "[\"abc\"]")));
        assertThrows(IOException.class, () -> TextureCache.load(
                Files.writeString(directory.resolve("b.json"), "{\"abc\": {\"nested\": true}}")));
    }

    @Test
    @DisplayName("no temporary files are left behind")
    void cleansUpTemporaryFiles(@TempDir Path directory) throws IOException {
        TextureCache cache = TextureCache.load(directory.resolve("cache.json"));
        cache.put("a", "1");
        cache.put("b", "2");

        try (var files = Files.list(directory)) {
            assertTrue(files.allMatch(path -> path.getFileName().toString().equals("cache.json")));
        }
    }
}
