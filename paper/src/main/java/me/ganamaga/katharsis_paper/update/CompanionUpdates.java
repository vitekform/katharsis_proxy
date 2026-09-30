package me.ganamaga.katharsis_paper.update;

import me.ganamaga.katharsis_updater.BuildInfo;
import me.ganamaga.katharsis_updater.Channel;
import me.ganamaga.katharsis_updater.ReleaseClient;
import me.ganamaga.katharsis_updater.Updater;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Keeps the companion up to date from the release system.
 *
 * <p>A newer jar for this Paper line is downloaded straight into the server's
 * update folder under this jar's own file name, which is where Paper looks
 * when it starts: the next restart swaps it in. {@code /kcompanion check} and
 * {@code /kcompanion update} do the same by hand, for
 * {@value #COMMAND_PERMISSION}; {@value #NOTIFY_PERMISSION} is told on joining.</p>
 */
public final class CompanionUpdates implements Listener, TabExecutor {

    public static final String NOTIFY_PERMISSION = "katharsis.update.notify";
    public static final String COMMAND_PERMISSION = "katharsis.update";

    private static final long TICKS_PER_SECOND = 20L;
    private static final long TICKS_PER_HOUR = TICKS_PER_SECOND * 60L * 60L;
    private static final long FIRST_CHECK_DELAY_TICKS = 10L * TICKS_PER_SECOND;

    private static final List<String> SUBCOMMANDS = List.of("check", "update");

    /** Used when config.yml predates the key. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "update-usage", "<gray>Katharsis companion <white><current></white>. <gold>/kcompanion check</gold> "
                    + "looks for an update, <gold>/kcompanion update</gold> downloads it.</gray>",
            "update-checking", "<gray>Checking for updates…</gray>",
            "update-up-to-date", "<green>The Katharsis companion <white><current></white> is up to date.</green>",
            "update-available", "<gold>Katharsis companion <white><latest></white> is available</gold> <gray>(this "
                    + "server runs <current>). <gold>/kcompanion update</gold> downloads it.</gray>",
            "update-downloaded", "<green>Katharsis companion <white><latest></white> is downloaded</green> <gray>and "
                    + "replaces <current> when this server restarts.</gray>",
            "update-no-file", "<red>Katharsis <white><latest></white> is out, but it has no companion jar for this "
                    + "Paper version.</red>",
            "update-failed", "<red>Could not update: <white><error></white></red>",
            "update-disabled", "<gray>Update checks are off in config.yml (updates.enabled).</gray>");

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ConfigurationSection config;
    private final ConfigurationSection messages;
    private final Updater updater;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private CompanionUpdates(JavaPlugin plugin, ConfigurationSection config, ConfigurationSection messages,
                             Updater updater) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.messages = messages;
        this.updater = updater;
    }

    /**
     * @param jar this plugin's own jar, whose name the update must carry
     * @return null if the jar does not say what it is, which only a broken
     *         build can cause
     */
    public static CompanionUpdates create(JavaPlugin plugin, File jar) {
        Logger logger = plugin.getLogger();
        BuildInfo build;
        try {
            build = BuildInfo.load(CompanionUpdates.class);
        } catch (IOException e) {
            logger.warning("Cannot tell which Katharsis companion this is (" + e.getMessage()
                    + "); update checks are off.");
            return null;
        }
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("updates");
        if (config == null) {
            config = plugin.getConfig().createSection("updates");
        }
        ConfigurationSection messages = plugin.getConfig().getConfigurationSection("messages");
        if (messages == null) {
            messages = plugin.getConfig().createSection("messages");
        }
        File updateFolder = plugin.getServer().getUpdateFolderFile();
        ReleaseClient client = new ReleaseClient(ReleaseClient.defaultHttpClient(), config.getString("url", ""),
                "Katharsis-Companion/" + build.version() + " (" + plugin.getServer().getName() + " "
                        + plugin.getServer().getMinecraftVersion() + ")");
        Updater updater = new Updater(build, client,
                new Updater.Settings(Channel.parse(config.getString("channel", "auto")),
                        config.getBoolean("auto-download", true)),
                // Paper swaps a jar in from the update folder only under the same name.
                file -> updateFolder.toPath().resolve(jar.getName()), new Updater.Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warning(message);
            }
        });
        return new CompanionUpdates(plugin, config, messages, updater);
    }

    private boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    /** Schedules the checks and the notice on joining. */
    public void start() {
        BuildInfo build = updater.build();
        logger.info("This is Katharsis companion " + build.version() + (build.official() ? "" : " (a local build)")
                + (build.classifier().isEmpty() ? "" : " for " + build.classifier()) + ".");
        if (!enabled()) {
            logger.info("Update checks are off (updates.enabled).");
            return;
        }
        long hours = Math.max(0, config.getInt("check-interval-hours", 6));
        if (hours > 0) {
            plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, updater::check,
                    FIRST_CHECK_DELAY_TICKS, hours * TICKS_PER_HOUR);
        } else {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, updater::check,
                    FIRST_CHECK_DELAY_TICKS);
        }
        if (config.getBoolean("notify-staff", true)) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().hasPermission(NOTIFY_PERMISSION)) {
            updater.lastResult().filter(Updater.Result::updateAvailable)
                    .ifPresent(result -> tell(event.getPlayer(), result));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            sender.sendMessage(message("update-usage",
                    Placeholder.unparsed("current", updater.build().version().toString())));
            return true;
        }
        if (!enabled()) {
            sender.sendMessage(message("update-disabled"));
            return true;
        }
        boolean download = sub.equals("update");
        sender.sendMessage(message("update-checking"));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                tell(sender, download ? updater.checkAndDownload() : updater.check()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(typed)).toList();
    }

    private void tell(CommandSender sender, Updater.Result result) {
        TagResolver current = Placeholder.unparsed("current", result.running().toString());
        TagResolver latest = Placeholder.unparsed("latest",
                result.latest() == null ? "" : result.latest().version().toString());
        sender.sendMessage(switch (result.status()) {
            case UP_TO_DATE -> message("update-up-to-date", current);
            case AVAILABLE -> message("update-available", current, latest);
            case DOWNLOADED -> message("update-downloaded", current, latest);
            case NO_FILE -> message("update-no-file", latest);
            case FAILED -> message("update-failed",
                    Placeholder.unparsed("error", result.error() == null ? "" : result.error()));
        });
    }

    private Component message(String key, TagResolver... placeholders) {
        return miniMessage.deserialize(messages.getString(key, DEFAULT_MESSAGES.get(key)), placeholders);
    }
}
