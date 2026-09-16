package me.ganamaga.katharsis_proxy.motd.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.ganamaga.katharsis_proxy.motd.MotdLibrary;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds banner MOTDs from images: every 8x8 tile becomes a player head wearing
 * a skin with that tile as its face, and the heads are laid out in lines to
 * redraw the picture in the server list.
 *
 * <p>The JSON written is identical to the standalone generator script's, so
 * files from either are interchangeable.</p>
 */
public final class BannerGenerator {

    /** Kept in the plugin data directory, next to the images. */
    public static final String CACHE_FILE_NAME = "motd-skin-cache.json";

    /** Longest string a status response may carry. */
    static final int MAX_STATUS_JSON_LENGTH = 32_767;

    /** Compact, and without HTML escaping, which would turn base64 padding into {@code =}. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Logger logger;
    private final TextureUploader uploader;
    private final TextureCache cache;

    public BannerGenerator(Logger logger, TextureUploader uploader, TextureCache cache) {
        this.logger = logger;
        this.uploader = uploader;
        this.cache = cache;
    }

    /**
     * Regenerates {@code motd.json} through {@code motd<count>.json} from the
     * image of the same name, for every image newer than its JSON. A JSON file
     * with no image, or with an older one, is left untouched, so hand-written
     * banners are safe.
     *
     * <p>A failure on one image is logged and the rest are still processed.
     * Tiles uploaded before a failure stay cached, so the next run resumes
     * where this one stopped.</p>
     *
     * @return how many JSON files were written
     * @throws InterruptedException if interrupted, typically by proxy shutdown
     */
    public int refresh(Path dataDirectory, int count) throws InterruptedException {
        int written = 0;
        for (int index = 1; index <= count; index++) {
            Path image = dataDirectory.resolve(MotdLibrary.fileName(index, "png"));
            Path json = dataDirectory.resolve(MotdLibrary.fileName(index, "json"));
            try {
                if (!isStale(image, json)) {
                    continue;
                }
                logger.info("Generating {} from {}.", json.getFileName(), image.getFileName());
                String banner = generate(BannerImage.read(image), image.getFileName().toString());
                TextureCache.writeAtomically(json, banner);
                written++;
            } catch (IOException | IllegalArgumentException e) {
                logger.error("Could not generate {} from {}; leaving it as it was.",
                        json.getFileName(), image.getFileName(), e);
            }
        }
        return written;
    }

    /**
     * Builds the banner JSON for an image, uploading only tiles the cache does
     * not already hold.
     *
     * @param name what to call the image in log messages
     * @throws IllegalArgumentException if the image is smaller than one tile
     */
    public String generate(BannerImage image, String name) throws IOException, InterruptedException {
        if (image.columns() == 0 || image.rows() == 0) {
            throw new IllegalArgumentException(name + " is " + image.width() + "x" + image.height()
                    + "px, smaller than a single " + BannerImage.TILE_SIZE + "px head.");
        }
        for (String warning : image.layoutWarnings()) {
            logger.warn("{} {}", name, warning);
        }

        List<BannerImage.Tile> tiles = image.tiles();
        JsonArray extra = new JsonArray();
        int uploaded = 0;
        for (int i = 0; i < tiles.size(); i++) {
            BannerImage.Tile tile = tiles.get(i);
            String hash = tile.hash();
            String value = cache.get(hash).orElse(null);
            if (value == null) {
                logger.info("[{}/{}] Uploading a tile of {} to MineSkin...", i + 1, tiles.size(), name);
                value = uploader.upload(tile.skinPng());
                cache.put(hash, value);
                uploaded++;
            }
            extra.add(head(value));

            if (tile.column() == image.columns() - 1) {
                extra.add(lineEnd(tile.row() < image.rows() - 1));
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        root.addProperty("color", "white");
        root.addProperty("shadow_color", -1);
        root.add("extra", extra);
        String json = GSON.toJson(root);

        logger.info("Built a {}x{} banner from {}: {} tile(s) uploaded, {} from the cache.",
                image.columns(), image.rows(), name, uploaded, tiles.size() - uploaded);
        if (json.length() > MAX_STATUS_JSON_LENGTH) {
            logger.warn("The banner from {} is {} characters, over the {} a status response allows; "
                            + "clients may refuse the ping. Use a smaller image.",
                    name, json.length(), MAX_STATUS_JSON_LENGTH);
        }
        return json;
    }

    private static boolean isStale(Path image, Path json) throws IOException {
        if (Files.notExists(image)) {
            return false;
        }
        return Files.notExists(json)
                || Files.getLastModifiedTime(image).compareTo(Files.getLastModifiedTime(json)) > 0;
    }

    /** A player head drawn from the given texture, with its hat layer shown. */
    private static JsonObject head(String textureValue) {
        JsonObject property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", textureValue);
        JsonArray properties = new JsonArray();
        properties.add(property);

        JsonObject player = new JsonObject();
        player.addProperty("name", "");
        player.add("properties", properties);

        JsonObject head = new JsonObject();
        head.addProperty("hat", true);
        head.add("player", player);
        return head;
    }

    /**
     * Ends a line of heads the way the standalone generator does: a black full
     * stop without a shadow, followed by a line break on every line but the last.
     */
    private static JsonObject lineEnd(boolean breakLine) {
        JsonObject end = new JsonObject();
        end.addProperty("text", ".");
        end.addProperty("color", "black");
        end.addProperty("shadow_color", 0);
        if (breakLine) {
            JsonArray newline = new JsonArray();
            newline.add("\n");
            end.add("extra", newline);
        }
        return end;
    }
}
