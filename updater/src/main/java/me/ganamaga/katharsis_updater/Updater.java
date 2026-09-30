package me.ganamaga.katharsis_updater;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Checks the release system for a newer build of the running jar and, when
 * asked to, downloads it. Installing is left to the platform, because each
 * one can swap a jar at a different moment: Paper from its update folder on
 * the next start, Velocity and NeoForge only once they stop (see
 * {@link JarFiles#install}).
 *
 * <p>Every method blocks on the network; call them off the main thread.
 * They are synchronized, so a scheduled check and a command cannot race.</p>
 */
public final class Updater {

    /** Where the console lines go. */
    public interface Log {
        void info(String message);

        void warn(String message);
    }

    /**
     * @param autoDownload fetch a newer jar as soon as it is found, so the next
     *                     restart runs it. Only ever for an {@linkplain
     *                     BuildInfo#official() official} jar.
     */
    public record Settings(Channel channel, boolean autoDownload) {
    }

    public enum Status {
        /** Nothing newer on the channel. */
        UP_TO_DATE,
        /** A newer release exists and has not been downloaded. */
        AVAILABLE,
        /** A newer release has been downloaded and is waiting for a restart. */
        DOWNLOADED,
        /** A newer release exists but has no jar for this platform and variant. */
        NO_FILE,
        /** The release system could not be asked, or the download failed. */
        FAILED
    }

    /**
     * What the last check found.
     *
     * @param latest the newest release, when the check got that far
     * @param file   its jar for this build, when there is one
     * @param error  why it failed, for {@link Status#FAILED}
     */
    public record Result(Status status, Version running, Release latest, ReleaseFile file, String error) {

        public boolean updateAvailable() {
            return status == Status.AVAILABLE || status == Status.DOWNLOADED;
        }
    }

    /** A verified jar on disk, waiting to be installed. */
    public record Download(Release release, ReleaseFile file, Path path) {
    }

    private final BuildInfo build;
    private final ReleaseClient client;
    private final Settings settings;
    private final Function<ReleaseFile, Path> destination;
    private final Log log;

    private Result last;
    private Download downloaded;
    /** The newest version already announced in the console, so repeated checks stay quiet. */
    private Version announced;

    /**
     * @param destination where a release file is downloaded to
     */
    public Updater(BuildInfo build, ReleaseClient client, Settings settings,
                   Function<ReleaseFile, Path> destination, Log log) {
        this.build = build;
        this.client = client;
        this.settings = settings;
        this.destination = destination;
        this.log = log;
    }

    public BuildInfo build() {
        return build;
    }

    public Settings settings() {
        return settings;
    }

    /** The last check's result, if any check has run. */
    public synchronized Optional<Result> lastResult() {
        return Optional.ofNullable(last);
    }

    /** The jar downloaded since startup, if any. */
    public synchronized Optional<Download> downloaded() {
        return Optional.ofNullable(downloaded);
    }

    /**
     * Asks the release system what is newest, logs anything new, and downloads
     * it if {@link Settings#autoDownload} says to.
     */
    public synchronized Result check() {
        boolean prereleases = settings.channel().includesPrereleases(build.version());
        Optional<Release> found;
        try {
            found = client.latest(prereleases, build.version());
        } catch (IOException e) {
            return record(failure(null, null, "could not reach " + client.baseUrl() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return record(failure(null, null, "interrupted"));
        }
        if (found.isEmpty() || !found.get().version().isNewerThan(build.version())) {
            return record(new Result(Status.UP_TO_DATE, build.version(), found.orElse(null), null, null));
        }
        Release release = found.get();
        Optional<ReleaseFile> file = release.fileFor(build);
        if (file.isEmpty()) {
            if (!release.version().equals(announced)) {
                announced = release.version();
                log.warn("Katharsis " + release.version() + " is out, but it has no "
                        + describeJar() + " jar to update to. You are running " + build.version() + ".");
            }
            return record(new Result(Status.NO_FILE, build.version(), release, null, null));
        }
        if (downloaded != null && downloaded.release().version().equals(release.version())) {
            return record(new Result(Status.DOWNLOADED, build.version(), release, file.get(), null));
        }
        if (!release.version().equals(announced)) {
            announced = release.version();
            log.warn("Katharsis " + release.version() + " is available; you are running " + build.version()
                    + missed(release) + ".");
        }
        if (settings.autoDownload() && build.official()) {
            return download(release, file.get());
        }
        if (settings.autoDownload()) {
            log.info("This jar was not built by CI, so it is not replaced automatically. "
                    + "Download " + file.get().filename() + " by hand or with the update command.");
        }
        return record(new Result(Status.AVAILABLE, build.version(), release, file.get(), null));
    }

    /**
     * Checks, then downloads whatever is newer even if automatic downloads
     * are off or this jar was built by hand. What an admin's update command
     * runs.
     */
    public synchronized Result checkAndDownload() {
        Result result = check();
        if (result.status() == Status.AVAILABLE) {
            return download(result.latest(), result.file());
        }
        return result;
    }

    private Result download(Release release, ReleaseFile file) {
        Path target = destination.apply(file);
        try {
            client.download(file, target);
        } catch (IOException e) {
            log.warn("Could not download Katharsis " + release.version() + ": " + e.getMessage());
            return record(failure(release, file, "download failed: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return record(failure(release, file, "interrupted"));
        }
        downloaded = new Download(release, file, target);
        log.info("Downloaded Katharsis " + release.version() + " (" + file.filename()
                + "); it takes over at the next restart.");
        return record(new Result(Status.DOWNLOADED, build.version(), release, file, null));
    }

    private Result failure(Release release, ReleaseFile file, String error) {
        return new Result(Status.FAILED, build.version(), release, file, error);
    }

    private Result record(Result result) {
        if (result.status() == Status.FAILED && (last == null || last.status() != Status.FAILED)) {
            // Once per outage, not once per check.
            log.warn("Update check failed: " + result.error());
        }
        last = result;
        return result;
    }

    private String describeJar() {
        return build.classifier().isEmpty() ? build.artifact() : build.artifact() + " " + build.classifier();
    }

    private static String missed(Release release) {
        int behind = release.history().size();
        return behind > 1 ? ", " + behind + " releases behind" : "";
    }

    /**
     * Deletes every jar left in a staging folder by an earlier run, which
     * either was installed or is stale by now.
     */
    public static void clearStaging(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.{jar,part}")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }
}
