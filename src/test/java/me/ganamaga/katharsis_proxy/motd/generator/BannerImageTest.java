package me.ganamaga.katharsis_proxy.motd.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerImageTest {

    private BannerImage fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/motd/generator/banner.png")) {
            return BannerImage.of(ImageIO.read(in));
        }
    }

    private static BannerImage blank(int width, int height) {
        return BannerImage.of(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }

    @Test
    @DisplayName("tile hashes are the ones Pillow computes, so the script's cache is reusable")
    void hashesMatchPillow() throws IOException {
        // hashlib.md5(block.tobytes()).hexdigest() for each tile of banner.png,
        // left to right then top to bottom.
        List<String> pillow = List.of(
                "61a295277175bd49a11fcd6b700c73fa",
                "61a295277175bd49a11fcd6b700c73fa",
                "57bdff11ac49c6e39f1573d544791ee8",
                "f6ba180f3decf0a3528620c64aceb26f",
                "01e8d75ab8b6830bdebe2b22a5bf19e4",
                "aef0c7729e1bf1bdcd8228864f7f0968");

        assertEquals(pillow, fixture().tiles().stream().map(BannerImage.Tile::hash).toList());
    }

    @Test
    @DisplayName("an invisible tile still hashes by its colour, as Pillow does")
    void transparentColourIsPartOfTheHash() throws IOException {
        // Tile (2,0) is red at zero alpha. Zeroing it would collide with any
        // other fully transparent tile and miss the script's cache entry.
        String invisibleRed = fixture().tiles().get(2).hash();

        assertEquals("57bdff11ac49c6e39f1573d544791ee8", invisibleRed);
        assertTrue(!invisibleRed.equals(blank(8, 8).tiles().getFirst().hash()));
    }

    @Test
    @DisplayName("tiles run left to right, then top to bottom")
    void tileOrder() throws IOException {
        List<BannerImage.Tile> tiles = fixture().tiles();

        assertEquals(6, tiles.size());
        assertEquals(List.of(0, 1, 2, 0, 1, 2), tiles.stream().map(BannerImage.Tile::column).toList());
        assertEquals(List.of(0, 0, 0, 1, 1, 1), tiles.stream().map(BannerImage.Tile::row).toList());
    }

    @Test
    @DisplayName("the skin is 64x64 with the tile on the face and nothing else")
    void skinHasTheTileOnTheFace() throws IOException {
        BannerImage.Tile tile = fixture().tiles().getFirst();

        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(tile.skinPng()));

        assertEquals(64, skin.getWidth());
        assertEquals(64, skin.getHeight());
        assertArrayEquals(tile.pixels(), skin.getRGB(8, 8, 8, 8, null, 0, 8));
        assertEquals(0, skin.getRGB(0, 0) >>> 24, "outside the face is transparent");
        assertEquals(0, skin.getRGB(16, 8) >>> 24, "the side of the head is transparent");
        assertEquals(0, skin.getRGB(40, 8) >>> 24, "the hat layer is transparent");
    }

    @Test
    @DisplayName("a 64x16 image fits the server list without warnings")
    void nativeSizeHasNoWarnings() {
        BannerImage image = blank(64, 16);

        assertEquals(8, image.columns());
        assertEquals(2, image.rows());
        assertEquals(List.of(), image.layoutWarnings());
    }

    @Test
    @DisplayName("a height other than 16 is warned about")
    void warnsAboutHeight() {
        List<String> warnings = blank(64, 64).layoutWarnings();

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("64px tall"));
    }

    @Test
    @DisplayName("pixels past the last whole tile are dropped, with a warning")
    void warnsAboutTrimming() {
        BannerImage image = blank(20, 16);

        assertEquals(2, image.columns());
        assertEquals(4, image.tiles().size());
        assertTrue(image.layoutWarnings().getFirst().contains("last 4px column"));
    }

    @Test
    @DisplayName("a file that is not an image is reported as such")
    void rejectsNonImages(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("motd.png"), "definitely not a png");

        IOException e = assertThrows(IOException.class, () -> BannerImage.read(file));
        assertTrue(e.getMessage().contains("motd.png"));
    }
}
