package me.ganamaga.katharsis_proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.command.ServerSwitchCommand;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.config.MotdConfig;
import me.ganamaga.katharsis_proxy.config.MotdMode;
import me.ganamaga.katharsis_proxy.database.ChatMessageRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.discord.DiscordBot;
import me.ganamaga.katharsis_proxy.discord.DiscordChatRelay;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.listener.PlayerJoinListener;
import me.ganamaga.katharsis_proxy.messaging.ChatChannel;
import me.ganamaga.katharsis_proxy.messaging.ChatChannelListener;
import me.ganamaga.katharsis_proxy.messaging.CompanionRegistry;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannel;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannelListener;
import me.ganamaga.katharsis_proxy.motd.MotdLibrary;
import me.ganamaga.katharsis_proxy.motd.MotdPingListener;
import me.ganamaga.katharsis_proxy.motd.generator.BannerGenerator;
import me.ganamaga.katharsis_proxy.motd.generator.MineSkinUploader;
import me.ganamaga.katharsis_proxy.motd.generator.TextureCache;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Katharsis_proxy {

    /** Every server switch command shares this cooldown. (global doesn't make sense) */
    /** Well technically it would make sense but I just chose to make it per server :D Get rekt */
    private static final Duration COMMAND_COOLDOWN = Duration.ofMinutes(1);

    /** How often expired language cache entries are reclaimed. */
    // You are allowed to guess how long is 1 :)
    private static final long CACHE_SWEEP_MINUTES = 1L;

    // fuck you velocity, provide normal color API for once
    public static final String RESET  = "\u001B[0m";
    public static final String RED    = "\u001B[31m";
    public static final String GREEN  = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxy;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private final List<ServerSwitchCommand> serverSwitchCommands = new ArrayList<>();
    private final PlayerLanguageCache languageCache = new PlayerLanguageCache();

    private ConfigManager configManager;
    private LanguageRegistry languageRegistry;
    private Messages messages;
    private DatabaseManager database;
    private PlayerRepository players;
    private ChatMessageRepository chatMessages;
    private MotdLibrary motdLibrary;
    private DiscordBot discordBot;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info(YELLOW + "Loading Katharsis Proxy...");
        loadConfig();
        loadLanguages();
        setUpDatabase();
        registerCommands();
        // Before plugin messaging, which relays chat to the bot.
        startDiscordBot();
        registerPluginMessaging();
        registerMotd();
        scheduleCacheSweep();
        logger.info(GREEN + "Katharsis Proxy loaded successfully.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        languageCache.clear();
        // Before the database closes, in case queued tasks still read from it.
        if (discordBot != null) {
            discordBot.stop();
        }
        if (database != null) {
            database.close();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // Drop the player's cooldowns so the maps do not grow without bound.
        for (ServerSwitchCommand command : serverSwitchCommands) {
            command.cooldowns().clear(playerId);
        }
        // Start the retention window. This lives here rather than in the join
        // listener so entries are still reclaimed when the database is down and
        // that listener was never registered.
        languageCache.expireLater(playerId);
    }

    /** Reads config.yml. On failure the built-in defaults are used instead. */
    private void loadConfig() {
        configManager = new ConfigManager(logger, dataDirectory);
        try {
            configManager.load();
            logger.info("Loaded configuration from {}.", configManager.configPath());
        } catch (ConfigurateException e) {
            logger.error(RED + "Could not load config.yml; continuing with the built-in defaults.", e);
        }
    }

    /**
     * Writes out the bundled language files and reads every language present.
     * On failure messages fall back to their keys, which is ugly but harmless.
     */
    private void loadLanguages() {
        languageRegistry = new LanguageRegistry(logger, dataDirectory);
        try {
            languageRegistry.load(configManager.config().defaultLanguage());
        } catch (ConfigurateException e) {
            logger.error(RED + "Could not load the language files; messages will show their keys.", e);
        }
        messages = new Messages(languageRegistry, languageCache);
    }

    /**
     * Opens the connection pool and starts persisting players. A failure here is
     * logged and leaves the rest of the plugin working, so the proxy still boots
     * without a reachable database.
     */
    private void setUpDatabase() {
        database = new DatabaseManager(logger, dataDirectory);
        players = new PlayerRepository(database);
        chatMessages = new ChatMessageRepository(database);
        try {
            database.start(configManager.config().database());
            players.createTable();
            chatMessages.createTable();
        } catch (SQLException e) {
            logger.error(RED + "Could not open the database; player data will not be stored.", e);
            database.close();
            return;
        }

        proxy.getEventManager().register(this,
                new PlayerJoinListener(logger, languageRegistry, database, players, languageCache));
        logger.info(GREEN + "Player entities will be created on first join.");
    }

    private void registerCommands() {
        registerServerSwitchCommand("lobby");
        registerServerSwitchCommand("lifesteal");
        logger.info("Registered {} server switch command(s) with a {}s cooldown.",
                serverSwitchCommands.size(), COMMAND_COOLDOWN.toSeconds());

        // No /language here. The companion plugin owns the selector, and two
        // commands on the same name would shadow each other unpredictably
        // depending on which registered last.
        logger.info("No /language is registered on the proxy; the companion plugin provides the selector.");
    }

    /**
     * Opens the language and chat channels to backend servers. Registered even
     * when the database is down, so language callers get an explicit error
     * instead of silence and chat reports are still kept away from clients.
     */
    private void registerPluginMessaging() {
        CompanionRegistry companions = new CompanionRegistry(logger);
        proxy.getChannelRegistrar().register(LanguageChannel.IDENTIFIER);
        proxy.getEventManager().register(this, new LanguageChannelListener(
                proxy, logger, languageRegistry, messages, database, players, languageCache, companions));
        logger.info("Serving language requests on the '{}' plugin message channel.",
                LanguageChannel.IDENTIFIER.getId());

        proxy.getChannelRegistrar().register(ChatChannel.IDENTIFIER);
        DiscordChatRelay discordRelay = new DiscordChatRelay(logger, discordBot, () -> configManager.config().discord());
        proxy.getEventManager().register(this, new ChatChannelListener(logger, database, chatMessages, discordRelay));
        logger.info("Logging chat reported on the '{}' plugin message channel.",
                ChatChannel.IDENTIFIER.getId());
        int relayed = configManager.config().discord().chatChannels().size();
        if (discordBot.isAccepting() && relayed > 0) {
            logger.info("Posting chat from {} server(s) to Discord.", relayed);
        }
    }

    /**
     * Takes over the server list ping with the banner MOTD, when it is turned
     * on. Everything here is optional: with the feature off, or with no banner
     * files present, the proxy keeps answering pings from velocity.toml.
     */
    private void registerMotd() {
        MotdConfig motd = configManager.config().motd();
        if (!motd.enabled()) {
            logger.info("The banner MOTD is off; pings are answered from velocity.toml.");
            return;
        }

        motdLibrary = new MotdLibrary(logger, dataDirectory);
        motdLibrary.load(motd.count());
        if (motdLibrary.isEmpty() && !motd.generator().enabled()) {
            logger.warn(RED +"The banner MOTD is on but no banner loaded from {}; every ping will use the fallback.",
                    dataDirectory);
        }

        // The config is passed as a supplier so a later reload is picked up
        // without re-registering the listener.
        proxy.getEventManager().register(this,
                new MotdPingListener(() -> configManager.config().motd(), motdLibrary));

        // Keyed on the configured count rather than what loaded: banners generated
        // in the background arrive after this point, and advancing over fewer
        // than two is harmless.
        if (motd.mode() == MotdMode.ROLLING && motd.count() > 1 && motd.rollingIntervalSeconds() > 0L) {
            proxy.getScheduler().buildTask(this, motdLibrary::advance)
                    .repeat(motd.rollingIntervalSeconds(), TimeUnit.SECONDS)
                    .schedule();
            logger.info("Serving {} banner MOTD(s), rotating every {}s.",
                    motdLibrary.size(), motd.rollingIntervalSeconds());
        } else {
            logger.info("Serving {} banner MOTD(s) to clients on protocol {}-{}.",
                    motdLibrary.size(), motd.minProtocol(), motd.maxProtocol());
        }

        if (motd.generator().enabled()) {
            generateBanners(motd);
        }
    }

    /**
     * Builds banner JSON from motd.png and friends, off the startup thread.
     * MineSkin allows roughly one upload every six seconds, so a new image can
     * take a minute or more; until it is done pings get whatever was already
     * loaded, or the fallback.
     */
    private void generateBanners(MotdConfig motd) {
        proxy.getScheduler().buildTask(this, () -> {
            try {
                BannerGenerator generator = new BannerGenerator(logger,
                        MineSkinUploader.fromConfig(logger, motd.generator()),
                        TextureCache.load(dataDirectory.resolve(BannerGenerator.CACHE_FILE_NAME)));
                if (generator.refresh(dataDirectory, motd.count()) > 0) {
                    motdLibrary.load(motd.count());
                    logger.info("Now serving {} banner MOTD(s) after generating from images.", motdLibrary.size());
                }
            } catch (IOException e) {
                logger.error(RED + "Could not read {}; no banners were generated from images.",
                        BannerGenerator.CACHE_FILE_NAME, e);
            } catch (IllegalArgumentException e) {
                logger.error(RED + "motd.generator.mineskin-url is not a valid URL; no banners were generated.", e);
            } catch (InterruptedException e) {
                // The proxy is shutting down. Uploaded tiles are already cached.
                Thread.currentThread().interrupt();
                return;
            }
            if (motdLibrary.isEmpty()) {
                logger.warn(RED + "The banner MOTD is on but no banner loaded from {}; every ping will use the fallback.",
                        dataDirectory);
            }
        }).schedule();
    }

    /**
     * Logs the Discord bot in on its own thread. The bot is created even when
     * it is off, so {@link #discordBot()} is always usable and simply rejects
     * tasks.
     */
    private void startDiscordBot() {
        discordBot = new DiscordBot(logger);
        DiscordConfig discord = configManager.config().discord();
        if (!discord.enabled()) {
            logger.info("The Discord bot is off.");
            return;
        }
        if (discord.token().isBlank()) {
            logger.warn(RED + "The Discord bot is on but discord.token is empty; it will not start.");
            return;
        }
        discordBot.start(discord.token().strip());
    }

    /**
     * The Discord bot. Submit work to it from any thread; it runs the tasks in
     * order on its own thread.
     */
    public DiscordBot discordBot() {
        return discordBot;
    }

    /**
     * Reclaims cache entries whose retention window has passed. Lookups already
     * honour the expiry, so this only frees memory.
     */
    private void scheduleCacheSweep() {
        proxy.getScheduler().buildTask(this, () -> {
            int evicted = languageCache.evictExpired();
            if (evicted > 0) {
                logger.debug("Dropped {} cached language(s); {} remaining.", evicted, languageCache.size());
            }
        }).repeat(CACHE_SWEEP_MINUTES, TimeUnit.MINUTES).schedule();
        logger.info("Caching player languages for {} minute(s) after disconnect.",
                PlayerLanguageCache.RETENTION.toMinutes());
    }

    private void registerServerSwitchCommand(String serverName) {
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder(serverName)
                .plugin(this)
                .build();
        ServerSwitchCommand command =
                new ServerSwitchCommand(proxy, logger, messages, serverName, COMMAND_COOLDOWN);
        commandManager.register(meta, command);
        serverSwitchCommands.add(command);

        if (proxy.getServer(serverName).isEmpty()) {
            logger.warn(RED + "Registered /{} but no server named '{}' exists in velocity.toml.", serverName, serverName);
        } else {
            logger.info(GREEN + "Registered /{} -> server '{}'.", serverName, serverName);
        }
    }
}
