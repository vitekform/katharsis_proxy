package me.ganamaga.katharsis_neoforge.update;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_updater.BuildInfo;
import me.ganamaga.katharsis_updater.Channel;
import me.ganamaga.katharsis_updater.JarFiles;
import me.ganamaga.katharsis_updater.ReleaseClient;
import me.ganamaga.katharsis_updater.Updater;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the mod up to date from the release system.
 *
 * <p>Settings live in {@code katharsis-updates.properties} in the config folder, written with
 * their defaults on first start. A newer jar for this Minecraft line is
 * downloaded to {@code katharsis/updates/} in the game folder and moved over
 * this one in {@code mods/} once the server has stopped, so the next start
 * runs it. There is no command: this mod has no other reason to reach into
 * the server's command and permission APIs, which move between versions.</p>
 */
public final class ModUpdates {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long FIRST_CHECK_DELAY_SECONDS = 10L;

    private final Updater updater;
    private final Path modsDirectory;
    private final Path staging;
    private final UpdateSettings settings;
    private ScheduledExecutorService executor;

    private ModUpdates(Updater updater, Path modsDirectory, Path staging, UpdateSettings settings) {
        this.updater = updater;
        this.modsDirectory = modsDirectory;
        this.staging = staging;
        this.settings = settings;
    }

    /**
     * @return empty if the jar does not say what it is, which only a broken
     *         build can cause
     */
    public static Optional<ModUpdates> create(Path configDirectory, Path gameDirectory, Path modsDirectory) {
        BuildInfo build;
        try {
            build = BuildInfo.load(ModUpdates.class);
        } catch (IOException e) {
            LOGGER.warn("Cannot tell which Katharsis mod this is ({}); update checks are off.", e.getMessage());
            return Optional.empty();
        }
        Path settingsFile = configDirectory.resolve(UpdateSettings.FILE_NAME);
        UpdateSettings settings;
        try {
            settings = UpdateSettings.load(settingsFile);
        } catch (IOException e) {
            LOGGER.warn("Could not read {} ({}); using the default update settings.", settingsFile, e.getMessage());
            settings = UpdateSettings.defaults();
        }
        Path staging = gameDirectory.resolve("katharsis").resolve("updates");
        try {
            // Whatever an earlier run left here was installed or is out of date.
            Updater.clearStaging(staging);
        } catch (IOException e) {
            LOGGER.warn("Could not clear {}: {}", staging, e.getMessage());
        }
        ReleaseClient client = new ReleaseClient(ReleaseClient.defaultHttpClient(),
                settings.url(), "Katharsis-NeoForge/" + build.version());
        Updater updater = new Updater(build, client,
                new Updater.Settings(Channel.parse(settings.channel()), settings.autoDownload()),
                file -> staging.resolve(file.filename()), new Updater.Log() {
            @Override
            public void info(String message) {
                LOGGER.info(message);
            }

            @Override
            public void warn(String message) {
                LOGGER.warn(message);
            }
        });
        return Optional.of(new ModUpdates(updater, modsDirectory, staging, settings));
    }

    /** Starts checking, on a thread of its own. */
    public void start() {
        BuildInfo build = updater.build();
        LOGGER.info("This is Katharsis mod {}{}{}.", build.version(), build.official() ? "" : " (a local build)",
                build.classifier().isEmpty() ? "" : " for " + build.classifier());
        if (!settings.enabled()) {
            LOGGER.info("Update checks are off (enabled=false in {}).", UpdateSettings.FILE_NAME);
            return;
        }
        long hours = settings.checkIntervalHours();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "katharsis-updates");
            thread.setDaemon(true);
            return thread;
        });
        if (hours > 0) {
            executor.scheduleWithFixedDelay(this::check, FIRST_CHECK_DELAY_SECONDS,
                    TimeUnit.HOURS.toSeconds(hours), TimeUnit.SECONDS);
        } else {
            executor.schedule(this::check, FIRST_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void check() {
        try {
            updater.check();
        } catch (RuntimeException e) {
            // A scheduled task that throws is never run again.
            LOGGER.warn("Update check failed", e);
        }
    }

    /**
     * Stops checking and puts a downloaded jar in place of this one. Called
     * once the server has stopped.
     */
    public void stopAndInstall() {
        if (executor != null) {
            executor.shutdownNow();
        }
        Optional<Updater.Download> pending = updater.downloaded();
        if (pending.isEmpty()) {
            return;
        }
        Updater.Download download = pending.get();
        try {
            Optional<Path> running = JarFiles.jarOf(ModUpdates.class);
            if (running.isEmpty()) {
                // NeoForge loads mods from its own file system, so ask the folder instead.
                running = JarFiles.findJarContaining(modsDirectory, ModUpdates.class);
            }
            if (running.isEmpty()) {
                LOGGER.warn("Could not find the Katharsis mod jar in {}; install {} from {} by hand.",
                        modsDirectory, download.file().filename(), staging);
                return;
            }
            Path installed = JarFiles.install(download.path(), running.get(), download.file().filename());
            LOGGER.info("Installed Katharsis mod {} as {}; it runs from the next start.",
                    download.release().version(), installed.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Could not install Katharsis mod {}: {}. Replace the jar in mods/ with {} by hand.",
                    download.release().version(), e.getMessage(), download.path());
        }
    }
}
