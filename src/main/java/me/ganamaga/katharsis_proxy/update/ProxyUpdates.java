package me.ganamaga.katharsis_proxy.update;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.config.UpdatesConfig;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_updater.BuildInfo;
import me.ganamaga.katharsis_updater.Channel;
import me.ganamaga.katharsis_updater.JarFiles;
import me.ganamaga.katharsis_updater.ReleaseClient;
import me.ganamaga.katharsis_updater.Updater;
import me.ganamaga.katharsis_updater.Version;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the proxy plugin up to date from the release system.
 *
 * <p>Checks at startup and every {@code updates.check-interval-hours}; with
 * {@code auto-download} on, a newer jar is downloaded to {@code updates/} in
 * the data directory straight away. Velocity has no update folder, and a jar
 * cannot be swapped under a running plugin on every system, so it is moved
 * into {@code plugins/} as the proxy shuts down and runs from the next start.
 * Staff with {@value #NOTIFY_PERMISSION} are told when they join.</p>
 */
public final class ProxyUpdates {

    /** Told about an update on joining. */
    public static final String NOTIFY_PERMISSION = "katharsis.update.notify";

    /** May run /katharsis check and /katharsis update. */
    public static final String COMMAND_PERMISSION = "katharsis.update";

    /** Seconds after startup before the first check, so it does not crowd the boot log. */
    private static final long FIRST_CHECK_DELAY_SECONDS = 10L;

    private static final String STAGING_FOLDER = "updates";

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final UpdatesConfig config;
    private final Path staging;
    private final Updater updater;

    private ProxyUpdates(Object plugin, ProxyServer proxy, Logger logger, Messages messages, UpdatesConfig config,
                         Path staging, Updater updater) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.config = config;
        this.staging = staging;
        this.updater = updater;
    }

    /**
     * Reads what this jar is and prepares the updater. Checks nothing yet.
     *
     * @return empty if the jar does not say what it is, which only a broken
     *         build can cause
     */
    public static Optional<ProxyUpdates> create(Object plugin, ProxyServer proxy, Logger logger, Messages messages,
                                                UpdatesConfig config, Path dataDirectory, String version) {
        BuildInfo build;
        try {
            build = BuildInfo.load(plugin.getClass());
        } catch (IOException e) {
            logger.warn("Cannot tell which Katharsis this is ({}); update checks are off.", e.getMessage());
            return Optional.empty();
        }
        Path staging = dataDirectory.resolve(STAGING_FOLDER);
        try {
            // Whatever an earlier run left here was installed or is out of date.
            Updater.clearStaging(staging);
        } catch (IOException e) {
            logger.warn("Could not clear {}: {}", staging, e.getMessage());
        }
        ReleaseClient client = new ReleaseClient(ReleaseClient.defaultHttpClient(), config.url(),
                "Katharsis-Proxy/" + build.version() + " (Velocity " + version + ")");
        Updater updater = new Updater(build, client,
                new Updater.Settings(Channel.parse(config.channel()), config.autoDownload()),
                file -> staging.resolve(file.filename()), new Updater.Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }
        });
        return Optional.of(new ProxyUpdates(plugin, proxy, logger, messages, config, staging, updater));
    }

    public Version version() {
        return updater.build().version();
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** Schedules the checks and the staff notice. */
    public void start() {
        BuildInfo build = updater.build();
        logger.info("This is Katharsis {}{}.", build.version(), build.official() ? "" : " (a local build)");
        if (!config.enabled()) {
            logger.info("Update checks are off (updates.enabled).");
            return;
        }
        var task = proxy.getScheduler().buildTask(plugin, updater::check)
                .delay(FIRST_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
        if (config.checkIntervalHours() > 0) {
            task.repeat(config.checkIntervalHours(), TimeUnit.HOURS);
        }
        task.schedule();
        if (config.notifyStaff()) {
            proxy.getEventManager().register(plugin, this);
        }
        boolean dev = updater.settings().channel().includesPrereleases(build.version());
        boolean download = config.autoDownload() && build.official();
        logger.info("Checking for new {} {}{}.", dev ? "dev builds" : "releases",
                config.checkIntervalHours() > 0 ? "every " + config.checkIntervalHours() + " hour(s)" : "at startup",
                download ? "; they are downloaded and installed at the next restart" : "");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(NOTIFY_PERMISSION)) {
            updater.lastResult().filter(Updater.Result::updateAvailable)
                    .ifPresent(result -> tell(player, result));
        }
    }

    /**
     * Runs a check, and a download too if {@code download}, off the calling
     * thread, then tells {@code source} what came of it.
     */
    public void run(CommandSource source, boolean download) {
        messages.send(source, MessageKey.UPDATE_CHECKING);
        proxy.getScheduler().buildTask(plugin, () -> {
            Updater.Result result = download ? updater.checkAndDownload() : updater.check();
            tell(source, result);
        }).schedule();
    }

    private void tell(CommandSource source, Updater.Result result) {
        String latest = result.latest() == null ? "" : result.latest().version().toString();
        var current = Placeholder.unparsed("current", result.running().toString());
        var newest = Placeholder.unparsed("latest", latest);
        switch (result.status()) {
            case UP_TO_DATE -> messages.send(source, MessageKey.UPDATE_UP_TO_DATE, current);
            case AVAILABLE -> messages.send(source, MessageKey.UPDATE_AVAILABLE, current, newest);
            case DOWNLOADED -> messages.send(source, MessageKey.UPDATE_DOWNLOADED, current, newest);
            case NO_FILE -> messages.send(source, MessageKey.UPDATE_NO_FILE, newest);
            case FAILED -> messages.send(source, MessageKey.UPDATE_FAILED,
                    Placeholder.unparsed("error", result.error() == null ? "" : result.error()));
        }
    }

    /**
     * Puts a downloaded jar in place of this one. Called as the proxy shuts
     * down, when nothing more is loaded from the old jar that it has not
     * already opened.
     */
    public void installPending() {
        Optional<Updater.Download> pending = updater.downloaded();
        if (pending.isEmpty()) {
            return;
        }
        Updater.Download download = pending.get();
        try {
            Optional<Path> running = JarFiles.jarOf(plugin.getClass());
            if (running.isEmpty()) {
                running = JarFiles.findJarContaining(staging.getParent().getParent(), plugin.getClass());
            }
            if (running.isEmpty()) {
                logger.warn("Could not find the running Katharsis jar; install {} from {} by hand.",
                        download.file().filename(), staging);
                return;
            }
            Path installed = JarFiles.install(download.path(), running.get(), download.file().filename());
            logger.info("Installed Katharsis {} as {}; it runs from the next start.",
                    download.release().version(), installed.getFileName());
        } catch (IOException e) {
            logger.warn("Could not install Katharsis {}: {}. Replace the jar in plugins/ with {} by hand.",
                    download.release().version(), e.getMessage(), download.path());
        }
    }
}
