package me.ganamaga.katharsis_proxy.motd.generator;

import me.ganamaga.katharsis_proxy.motd.MotdLibrary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The fixtures under {@code motd/generator} were produced by running the
 * standalone Python generator itself: {@code banner.png} is a 24x16 image with a
 * repeated tile, an invisible tile with non-zero colour and semi-transparent
 * pixels; {@code skin_cache.json} was seeded with a value for every tile using
 * Pillow's hashes, so the script ran without uploading; and
 * {@code expected-motd.json} is what it wrote.
 */
class BannerGeneratorTest {

    private static final int DISTINCT_TILES = 5;
    private static final int TOTAL_TILES = 6;

    private static final TextureUploader NEVER = png -> fail("nothing should have been uploaded");

    private BannerGenerator generator(TextureUploader uploader, Path cacheFile) throws IOException {
        return new BannerGenerator(LoggerFactory.getLogger(BannerGeneratorTest.class),
                uploader, TextureCache.load(cacheFile));
    }

    private Path copyFixture(String name, Path target) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/motd/generator/" + name)) {
            if (in == null) {
                throw new IOException("missing test fixture " + name);
            }
            Files.copy(in, target);
        }
        return target;
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/motd/generator/" + name)) {
            return new String(in.readAllBytes());
        }
    }

    /** Hands out a distinct, padded base64 value per upload and counts them. */
    private static final class CountingUploader implements TextureUploader {
        final AtomicInteger uploads = new AtomicInteger();

        @Override
        public String upload(byte[] skinPng) {
            return "dGV4dHVyZQ" + uploads.incrementAndGet() + "==";
        }
    }

    @Test
    @DisplayName("output is byte-for-byte what the standalone script writes, from its own cache")
    void matchesTheStandaloneScript(@TempDir Path directory) throws Exception {
        Path image = copyFixture("banner.png", directory.resolve("banner.png"));
        Path cache = copyFixture("skin_cache.json", directory.resolve("skin_cache.json"));

        String json = generator(NEVER, cache).generate(BannerImage.read(image), "banner.png");

        assertEquals(fixture("expected-motd.json"), json);
    }

    @Test
    @DisplayName("base64 padding is written as-is, not HTML-escaped")
    void doesNotEscapePadding(@TempDir Path directory) throws Exception {
        Path image = copyFixture("banner.png", directory.resolve("banner.png"));

        String json = generator(new CountingUploader(), directory.resolve("cache.json"))
                .generate(BannerImage.read(image), "banner.png");

        assertTrue(json.contains("==\"}"));
        assertFalse(json.contains("\\u003d"));
    }

    @Test
    @DisplayName("each distinct tile is uploaded once, and a rerun uploads nothing")
    void uploadsDistinctTilesOnce(@TempDir Path directory) throws Exception {
        Path image = copyFixture("banner.png", directory.resolve("banner.png"));
        Path cacheFile = directory.resolve("cache.json");
        CountingUploader uploader = new CountingUploader();

        generator(uploader, cacheFile).generate(BannerImage.read(image), "banner.png");
        assertEquals(DISTINCT_TILES, uploader.uploads.get(), "the repeated tile shares one upload");

        generator(uploader, cacheFile).generate(BannerImage.read(image), "banner.png");
        assertEquals(DISTINCT_TILES, uploader.uploads.get(), "everything came from the cache file");
    }

    @Test
    @DisplayName("the written banner loads into the library as player heads")
    void refreshedBannerLoads(@TempDir Path directory) throws Exception {
        copyFixture("banner.png", directory.resolve("motd.png"));

        int written = generator(new CountingUploader(), directory.resolve(BannerGenerator.CACHE_FILE_NAME))
                .refresh(directory, 1);

        assertEquals(1, written);
        Component banner = GsonComponentSerializer.gson().deserialize(
                Files.readString(directory.resolve("motd.json")));
        assertInstanceOf(ObjectComponent.class, banner.children().getFirst(), "heads survive parsing");
        assertEquals(TOTAL_TILES + 2, banner.children().size(), "six heads and two line ends");

        MotdLibrary library = new MotdLibrary(LoggerFactory.getLogger(BannerGeneratorTest.class), directory);
        library.load(1);
        assertEquals(1, library.size());
    }

    @Test
    @DisplayName("images are paired with the JSON of the same number")
    void pairsNumberedFiles(@TempDir Path directory) throws Exception {
        copyFixture("banner.png", directory.resolve("motd2.png"));

        int written = generator(new CountingUploader(), directory.resolve("cache.json")).refresh(directory, 2);

        assertEquals(1, written);
        assertTrue(Files.exists(directory.resolve("motd2.json")));
        assertFalse(Files.exists(directory.resolve("motd.json")), "motd.png does not exist");
    }

    @Test
    @DisplayName("JSON newer than its image is left alone")
    void skipsUpToDateJson(@TempDir Path directory) throws Exception {
        Path image = copyFixture("banner.png", directory.resolve("motd.png"));
        Path json = Files.writeString(directory.resolve("motd.json"), "{\"text\":\"hand written\"}");
        Files.setLastModifiedTime(image, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(json, FileTime.from(Instant.parse("2026-02-01T00:00:00Z")));

        int written = generator(NEVER, directory.resolve("cache.json")).refresh(directory, 1);

        assertEquals(0, written);
        assertEquals("{\"text\":\"hand written\"}", Files.readString(json));
    }

    @Test
    @DisplayName("an image newer than its JSON regenerates it")
    void regeneratesStaleJson(@TempDir Path directory) throws Exception {
        Path image = copyFixture("banner.png", directory.resolve("motd.png"));
        Path json = Files.writeString(directory.resolve("motd.json"), "{\"text\":\"old\"}");
        Files.setLastModifiedTime(json, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(image, FileTime.from(Instant.parse("2026-02-01T00:00:00Z")));

        int written = generator(new CountingUploader(), directory.resolve("cache.json")).refresh(directory, 1);

        assertEquals(1, written);
        assertTrue(Files.readString(json).contains("\"hat\":true"));
    }

    @Test
    @DisplayName("JSON with no image is never touched")
    void leavesJsonWithoutImage(@TempDir Path directory) throws Exception {
        Path json = Files.writeString(directory.resolve("motd.json"), "{\"text\":\"hand written\"}");

        int written = generator(NEVER, directory.resolve("cache.json")).refresh(directory, 1);

        assertEquals(0, written);
        assertEquals("{\"text\":\"hand written\"}", Files.readString(json));
    }

    @Test
    @DisplayName("a failed upload keeps what was uploaded, and the next run resumes")
    void resumesAfterFailure(@TempDir Path directory) throws Exception {
        copyFixture("banner.png", directory.resolve("motd.png"));
        Path cacheFile = directory.resolve("cache.json");
        AtomicInteger calls = new AtomicInteger();
        TextureUploader failsOnThird = png -> {
            if (calls.incrementAndGet() == 3) {
                throw new IOException("MineSkin is down");
            }
            return "dmFsdWU" + calls.get() + "=";
        };

        assertEquals(0, generator(failsOnThird, cacheFile).refresh(directory, 1));
        assertFalse(Files.exists(directory.resolve("motd.json")), "no half-built banner is written");
        assertEquals(2, TextureCache.load(cacheFile).size(), "the two successful uploads were kept");

        CountingUploader rest = new CountingUploader();
        assertEquals(1, generator(rest, cacheFile).refresh(directory, 1));
        assertEquals(DISTINCT_TILES - 2, rest.uploads.get(), "only the remaining tiles were uploaded");
    }

    @Test
    @DisplayName("one broken image does not stop the others")
    void continuesPastABrokenImage(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("motd.png"), "not a png");
        copyFixture("banner.png", directory.resolve("motd2.png"));

        int written = generator(new CountingUploader(), directory.resolve("cache.json")).refresh(directory, 2);

        assertEquals(1, written);
        assertFalse(Files.exists(directory.resolve("motd.json")));
        assertTrue(Files.exists(directory.resolve("motd2.json")));
    }

    @Test
    @DisplayName("an image smaller than one head is rejected")
    void rejectsTinyImages() {
        BannerImage tiny = BannerImage.of(new java.awt.image.BufferedImage(4, 16,
                java.awt.image.BufferedImage.TYPE_INT_ARGB));

        assertThrows(IllegalArgumentException.class, () -> new BannerGenerator(
                LoggerFactory.getLogger(BannerGeneratorTest.class), NEVER,
                TextureCache.load(Path.of("does-not-exist.json"))).generate(tiny, "tiny.png"));
    }
}
