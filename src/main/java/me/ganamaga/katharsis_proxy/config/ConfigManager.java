package me.ganamaga.katharsis_proxy.config;

import me.ganamaga.katharsis_proxy.entity.Player;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@code config.yml} from the plugin data directory, copying the
 * commented default out of the jar the first time the plugin runs.
 *
 * <p>The file is never written back: Configurate's YAML loader cannot round
 * trip comments, so re-saving would strip the documentation and anything the
 * admin added. Keys missing from the file simply keep their built-in
 * defaults.</p>
 */
public final class ConfigManager {

    private static final String FILE_NAME = "config.yml";

    private final Logger logger;
    private final Path dataDirectory;
    private final Path configPath;
    private final YamlConfigurationLoader loader;

    private volatile KatharsisConfig config = new KatharsisConfig();

    public ConfigManager(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configPath = dataDirectory.resolve(FILE_NAME);
        this.loader = YamlConfigurationLoader.builder()
                .path(configPath)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .build();
    }

    /**
     * (Re)reads the configuration file, creating it from the bundled default if
     * it does not exist yet.
     *
     * @throws ConfigurateException if the file cannot be created or parsed
     */
    public void load() throws ConfigurateException {
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(configPath)) {
                writeDefaultConfig();
                logger.info("Created a default {} in {}.", FILE_NAME, dataDirectory);
            }
        } catch (IOException e) {
            throw new ConfigurateException("Could not prepare " + configPath, e);
        }

        CommentedConfigurationNode root = loader.load();
        KatharsisConfig loaded = root.get(KatharsisConfig.class, new KatharsisConfig());

        this.config = loaded;
        validate(loaded);
    }

    /** The most recently loaded configuration. */
    public KatharsisConfig config() {
        return config;
    }

    /** Path of the file this manager reads, for logging. */
    public Path configPath() {
        return configPath;
    }

    private void writeDefaultConfig() throws IOException {
        try (InputStream defaults = ConfigManager.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
            if (defaults == null) {
                throw new IOException("The plugin jar does not contain a default " + FILE_NAME);
            }
            Files.copy(defaults, configPath);
        }
    }

    private void validate(KatharsisConfig loaded) {
        if (loaded.defaultLanguage().length() > Player.LANGUAGE_MAX_LENGTH) {
            logger.warn("default-language '{}' is longer than {} characters and will be rejected by the database.",
                    loaded.defaultLanguage(), Player.LANGUAGE_MAX_LENGTH);
        }
        if (loaded.database().type() == DatabaseType.POSTGRESQL
                && loaded.database().postgresql().password().isEmpty()) {
            logger.warn("No PostgreSQL password is set in {}.", FILE_NAME);
        }
        validateMotd(loaded.motd());
        validateDiscord(loaded.discord());
    }

    private void validateDiscord(DiscordConfig discord) {
        if (discord.enabled() && discord.token().isBlank()) {
            logger.warn("discord.enabled is true but no discord.token is set, so the bot will not start.");
        }
        if (discord.enabled() && discord.guildId() <= 0L) {
            logger.warn("discord.guild-id is not set, so /link is not available on Discord.");
        }
        if (discord.linkedRoleId() > 0L && discord.guildId() <= 0L) {
            logger.warn("discord.linked-role-id is set without discord.guild-id, so no role will be granted.");
        }
        discord.chatChannels().forEach((server, channel) -> {
            if (channel == null || channel <= 0L) {
                logger.warn("discord.chat-channels.{} is not a Discord channel id, so its chat is not relayed.",
                        server);
            }
        });
    }

    /**
     * Warns about MOTD settings that would silently do nothing. None of these
     * are fatal: the listener clamps or skips whatever it cannot use.
     */
    private void validateMotd(MotdConfig motd) {
        if (!motd.enabled()) {
            return;
        }
        if (motd.count() < 1) {
            logger.warn("motd.count is {}, so no banner will be loaded and every ping uses the fallback.",
                    motd.count());
        }
        if (motd.minProtocol() > motd.maxProtocol()) {
            logger.warn("motd.min-protocol ({}) is above motd.max-protocol ({}), so no client will be served a banner.",
                    motd.minProtocol(), motd.maxProtocol());
        }
        if (motd.mode() == MotdMode.ROLLING && motd.rollingIntervalSeconds() <= 0L) {
            logger.warn("motd.rolling-interval-seconds must be positive; rolling is disabled and the first banner is served.");
        }
        if (motd.fakePlayerCount() && (motd.fakePlayerPercentage() < 0 || motd.fakePlayerPercentage() > 100)) {
            logger.warn("motd.fake-player-percentage ({}) is outside 0-100 and will be clamped.",
                    motd.fakePlayerPercentage());
        }
        MotdGeneratorConfig generator = motd.generator();
        if (generator.enabled() && generator.maxAttempts() < 1) {
            logger.warn("motd.generator.max-attempts is {}; each tile is still tried once.", generator.maxAttempts());
        }
        if (generator.enabled() && (generator.uploadDelayMs() < 0L || generator.retryDelayMs() < 0L)) {
            logger.warn("motd.generator delays cannot be negative and are treated as zero.");
        }
    }
}
