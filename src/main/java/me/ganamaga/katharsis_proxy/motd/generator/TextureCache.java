package me.ganamaga.katharsis_proxy.motd.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skin texture values already obtained from MineSkin, keyed by
 * {@link BannerImage.Tile#hash()}. Uploads are slow and rate limited, so a tile
 * is only ever uploaded once, and a picture that shares tiles with an earlier
 * one costs nothing to regenerate.
 *
 * <p>The file is a flat JSON object of hash to value, the same format the
 * standalone generator script writes to {@code skin_cache.json}.</p>
 */
public final class TextureCache {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Map<String, String> values;

    private TextureCache(Path file, Map<String, String> values) {
        this.file = file;
        this.values = values;
    }

    /**
     * Reads the cache, or starts an empty one if the file does not exist yet.
     *
     * @throws IOException if the file exists but cannot be read or parsed. It is
     *                     not silently replaced: that would discard every upload
     *                     it records.
     */
    public static TextureCache load(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try {
                JsonElement root = JsonParser.parseString(Files.readString(file));
                if (!root.isJsonObject()) {
                    throw new IOException(file.getFileName() + " is not a JSON object");
                }
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    values.put(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                throw new IOException(file.getFileName() + " is not a valid texture cache", e);
            }
        }
        return new TextureCache(file, values);
    }

    public Optional<String> get(String hash) {
        return Optional.ofNullable(values.get(hash));
    }

    /**
     * Records a value and writes the cache straight away, so an interrupted
     * generation keeps every tile uploaded before it stopped.
     */
    public synchronized void put(String hash, String value) throws IOException {
        values.put(hash, value);
        JsonObject root = new JsonObject();
        values.forEach(root::addProperty);
        writeAtomically(file, GSON.toJson(root));
    }

    public int size() {
        return values.size();
    }

    /**
     * Writes through a temporary file in the same directory, so a reader never
     * sees half a file and a crash mid-write leaves the old one intact.
     */
    static void writeAtomically(Path target, String content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
