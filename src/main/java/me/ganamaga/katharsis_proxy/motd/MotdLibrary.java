package me.ganamaga.katharsis_proxy.motd;

import me.ganamaga.katharsis_proxy.config.MotdMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The banner MOTDs read from the plugin data directory.
 *
 * <p>Each is a chat component serialised as JSON, in {@code motd.json},
 * {@code motd2.json}, {@code motd3.json} and so on. They are parsed once here
 * rather than on every ping: a ping is answered on a network thread and
 * banners are large, so re-parsing per ping would put a JSON parse on the hot
 * path for no benefit.</p>
 *
 * <p>Reads are lock free. {@link #load(int)} swaps in a fresh immutable list,
 * so a ping in flight keeps serving the previous set instead of observing a
 * half-filled one.</p>
 */
public final class MotdLibrary {


    private final Logger logger;
    private final Path dataDirectory;

    private volatile List<Component> banners = List.of();
    private final AtomicInteger rollingIndex = new AtomicInteger();

    public MotdLibrary(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Reads {@code motd.json} through {@code motd<count>.json}, keeping the ones
     * that parse. A missing or malformed file is logged and skipped rather than
     * failing the load, so one bad banner does not take the ping down with it.
     *
     * @param count how many files to look for
     */
    public void load(int count) {
        List<Component> loaded = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            read(fileName(index, "json")).ifPresent(loaded::add);
        }
        this.banners = List.copyOf(loaded);
        this.rollingIndex.set(0);
    }

    /** How many banners are currently loaded. */
    public int size() {
        return banners.size();
    }

    public boolean isEmpty() {
        return banners.isEmpty();
    }

    /** Moves {@link MotdMode#ROLLING} on to the next banner. */
    public void advance() {
        rollingIndex.incrementAndGet();
    }

    /**
     * The banner to serve right now, or empty if none are loaded.
     *
     * @param mode how to choose between them
     */
    public Optional<Component> select(MotdMode mode) {
        List<Component> current = banners;
        if (current.isEmpty()) {
            return Optional.empty();
        }
        // floorMod rather than %, so the index keeps working once the counter
        // has run past Integer.MAX_VALUE and gone negative.
        int index = mode == MotdMode.ROLLING
                ? Math.floorMod(rollingIndex.get(), current.size())
                : ThreadLocalRandom.current().nextInt(current.size());
        return Optional.of(current.get(index));
    }

    /**
     * Name of the {@code index}th banner file with the given extension:
     * {@code motd.json}, {@code motd2.json} and so on. The first has no number,
     * matching what banner generators emit. Images use the same scheme, so
     * {@code motd2.png} is the source of {@code motd2.json}.
     */
    public static String fileName(int index, String extension) {
        return (index == 1 ? "motd" : "motd" + index) + "." + extension;
    }

    private Optional<Component> read(String name) {
        Path path = dataDirectory.resolve(name);
        if (Files.notExists(path)) {
            logger.warn("{} does not exist in {}; skipping it.", name, dataDirectory);
            return Optional.empty();
        }
        try {
            String json = Files.readString(path);
            Component banner = GsonComponentSerializer.gson().deserialize(json);
            logger.info("Loaded banner MOTD from {} ({} characters).", name, json.length());
            return Optional.of(banner);
        } catch (IOException e) {
            logger.warn("Could not read {}; skipping it.", name, e);
        } catch (RuntimeException e) {
            // The gson serializer throws unchecked on malformed input.
            logger.warn("{} is not a valid chat component; skipping it.", name, e);
        }
        return Optional.empty();
    }
}
