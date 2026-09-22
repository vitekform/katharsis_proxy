package me.ganamaga.katharsis_proxy.util;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code debug.log} in the plugin data directory: the detail an admin needs to
 * see what the plugin actually sent and received, kept out of the proxy's own
 * console where it would bury everything else.
 *
 * <p>Writing is best effort. Nothing here throws, and a file that cannot be
 * written is reported once and then left alone: a debug log is never worth
 * failing a chat message over.</p>
 *
 * <p>The file is opened for each line rather than held open, so it survives an
 * admin rotating or deleting it while the proxy runs, and nothing is lost when
 * the proxy is killed rather than stopped. Calls are network bound and few
 * enough per second that the extra open costs nothing worth saving.</p>
 */
public final class DebugLog {

    /** Writes nothing. What callers get when debug logging is off. */
    public static final DebugLog DISABLED = new DebugLog(null, null, null);

    public static final String FILE_NAME = "debug.log";

    /** Rotated past this size, so a busy evening cannot fill the disk. */
    private static final long MAX_BYTES = 5L * 1024L * 1024L;

    /** Local time, because the reader of this file runs the server. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Null when disabled, along with the other two. */
    private final Logger logger;
    private final Path path;
    private final Clock clock;

    /** Set by the first failed write; the file is not touched again after it. */
    private volatile boolean broken;

    DebugLog(Logger logger, Path path, Clock clock) {
        this.logger = logger;
        this.path = path;
        this.clock = clock;
    }

    /**
     * @param enabled what {@code debug-log} says in {@code config.yml}
     * @return a log writing to {@code debug.log} in {@code dataDirectory}, or
     *         {@link #DISABLED} when it is off or the directory is unusable
     */
    public static DebugLog create(Logger logger, Path dataDirectory, boolean enabled) {
        if (!enabled) {
            return DISABLED;
        }
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.warn("Could not prepare {} for {}; debug logging is off.", dataDirectory, FILE_NAME, e);
            return DISABLED;
        }
        DebugLog log = new DebugLog(logger, dataDirectory.resolve(FILE_NAME), Clock.systemDefaultZone());
        // Marks the restart, so it is clear which lines belong to this run.
        log.log("--- Katharsis started ---");
        return log;
    }

    /** Whether anything is written at all. */
    public boolean isEnabled() {
        return path != null;
    }

    /** The file written to, or {@code null} when disabled. For startup logging. */
    public Path path() {
        return path;
    }

    /**
     * Appends one timestamped line, substituting {@code {}} with {@code args}
     * the way slf4j does. Newlines in the arguments are escaped, so one call
     * stays one line and the file remains greppable.
     */
    public void log(String format, Object... args) {
        if (path == null || broken) {
            return;
        }
        String line = LocalDateTime.ofInstant(clock.instant(), clock.getZone()).format(STAMP)
                + " " + oneLine(format(format, args)) + System.lineSeparator();
        synchronized (this) {
            try {
                rotateIfFull();
                Files.writeString(path, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                broken = true;
                logger.warn("Could not write {}; debug logging is off for the rest of this run.", path, e);
            }
        }
    }

    /** Keeps one previous file, which is enough to read across a rotation. */
    private void rotateIfFull() throws IOException {
        if (!Files.exists(path) || Files.size(path) < MAX_BYTES) {
            return;
        }
        Files.move(path, path.resolveSibling(path.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** slf4j's {@code {}} substitution, so call sites read like the rest of the plugin. */
    static String format(String format, Object... args) {
        if (format == null || args == null || args.length == 0) {
            return String.valueOf(format);
        }
        StringBuilder out = new StringBuilder(format.length() + 16 * args.length);
        int argument = 0;
        int from = 0;
        while (argument < args.length) {
            int at = format.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            out.append(format, from, at).append(args[argument++]);
            from = at + 2;
        }
        return out.append(format, from, format.length()).toString();
    }

    private static String oneLine(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
