package me.ganamaga.katharsis_proxy.motd.generator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * An image cut into the 8x8 tiles a banner MOTD is made of. Every tile becomes
 * one player head in the server list, so the picture is rebuilt from
 * {@link #columns()} heads per line across {@link #rows()} lines.
 */
public final class BannerImage {

    /** Side of one tile, and of the face on a player head. */
    public static final int TILE_SIZE = 8;

    /** The server list shows two lines, so an image this tall fills it exactly. */
    public static final int NATIVE_HEIGHT = 2 * TILE_SIZE;

    private static final int SKIN_SIZE = 64;

    /** Where the front of the head sits in a skin texture. */
    private static final int FACE_OFFSET = 8;

    private final int width;
    private final int height;
    private final int[] argb;

    private BannerImage(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    /**
     * Reads an image from disk.
     *
     * @throws IOException if the file cannot be read or is not an image
     */
    public static BannerImage read(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException(path.getFileName() + " is not an image format that can be read");
        }
        return of(image);
    }

    /** Wraps an image already in memory. */
    public static BannerImage of(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        // getRGB rather than drawing onto a fresh canvas: compositing would zero
        // the colour of fully transparent pixels, and that colour is part of the
        // tile hash the standalone generator's cache is keyed on.
        return new BannerImage(width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int columns() {
        return width / TILE_SIZE;
    }

    public int rows() {
        return height / TILE_SIZE;
    }

    /**
     * Problems with the image's shape that still let a banner be built, worded
     * for an operator reading the log.
     */
    public List<String> layoutWarnings() {
        List<String> warnings = new ArrayList<>();
        if (height != NATIVE_HEIGHT) {
            warnings.add("is " + height + "px tall; the server list shows exactly two lines of "
                    + TILE_SIZE + "px heads, so it may be cut off. " + NATIVE_HEIGHT + "px tall images fit.");
        }
        int trimmedX = width % TILE_SIZE;
        int trimmedY = height % TILE_SIZE;
        if (trimmedX != 0 || trimmedY != 0) {
            warnings.add("is not a multiple of " + TILE_SIZE + "px; the last " + trimmedX
                    + "px column(s) and " + trimmedY + "px row(s) are dropped.");
        }
        return warnings;
    }

    /** Every whole tile, left to right and then top to bottom. */
    public List<Tile> tiles() {
        List<Tile> tiles = new ArrayList<>(columns() * rows());
        for (int row = 0; row < rows(); row++) {
            for (int column = 0; column < columns(); column++) {
                tiles.add(tile(column, row));
            }
        }
        return tiles;
    }

    private Tile tile(int column, int row) {
        int[] pixels = new int[TILE_SIZE * TILE_SIZE];
        for (int y = 0; y < TILE_SIZE; y++) {
            System.arraycopy(argb, (row * TILE_SIZE + y) * width + column * TILE_SIZE,
                    pixels, y * TILE_SIZE, TILE_SIZE);
        }
        return new Tile(column, row, pixels);
    }

    /**
     * One 8x8 piece of the image.
     *
     * @param pixels the tile's pixels as ARGB, row by row
     */
    public record Tile(int column, int row, int[] pixels) {

        /**
         * MD5 of the tile's pixels as RGBA bytes, row by row, in lowercase hex.
         * This is exactly what Pillow's {@code tobytes()} yields for an RGBA
         * image, so the key matches the standalone generator's cache and an
         * existing cache file can be reused.
         */
        public String hash() {
            byte[] rgba = new byte[pixels.length * 4];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                rgba[i * 4] = (byte) (pixel >>> 16);
                rgba[i * 4 + 1] = (byte) (pixel >>> 8);
                rgba[i * 4 + 2] = (byte) pixel;
                rgba[i * 4 + 3] = (byte) (pixel >>> 24);
            }
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(rgba));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Every JVM is required to provide MD5", e);
            }
        }

        /**
         * A 64x64 skin, transparent except for this tile painted over the front
         * of the head, encoded as PNG.
         */
        public byte[] skinPng() {
            BufferedImage skin = new BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB);
            skin.setRGB(FACE_OFFSET, FACE_OFFSET, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            try {
                ImageIO.write(skin, "png", png);
            } catch (IOException e) {
                // Only an in-memory stream is written to, which cannot fail.
                throw new UncheckedIOException(e);
            }
            return png.toByteArray();
        }
    }
}
