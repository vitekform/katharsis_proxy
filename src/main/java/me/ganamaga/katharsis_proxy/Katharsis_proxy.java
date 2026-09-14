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
import me.ganamaga.katharsis_proxy.config.MotdConfig;
import me.ganamaga.katharsis_proxy.config.MotdMode;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.listener.PlayerJoinListener;
import me.ganamaga.katharsis_proxy.messaging.CompanionRegistry;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannel;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannelListener;
import me.ganamaga.katharsis_proxy.motd.MotdLibrary;
import me.ganamaga.katharsis_proxy.motd.MotdPingListener;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;

import java.awt.*;
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
    private MotdLibrary motdLibrary;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info(YELLOW + "Loading Katharsis Proxy...");
        loadConfig();
        loadLanguages();
        setUpDatabase();
        registerCommands();
        registerPluginMessaging();
        registerMotd();
        scheduleCacheSweep();
        logger.info(GREEN + "Katharsis Proxy loaded successfully.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        languageCache.clear();
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
        try {
            database.start(configManager.config().database());
            players.createTable();
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
     * Opens the language channel to backend servers. Registered even when the
     * database is down, so callers get an explicit error instead of silence.
     */
    private void registerPluginMessaging() {
        CompanionRegistry companions = new CompanionRegistry(logger);
        proxy.getChannelRegistrar().register(LanguageChannel.IDENTIFIER);
        proxy.getEventManager().register(this, new LanguageChannelListener(
                proxy, logger, languageRegistry, messages, database, players, languageCache, companions));
        logger.info("Serving language requests on the '{}' plugin message channel.",
                LanguageChannel.IDENTIFIER.getId());
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
        if (motdLibrary.isEmpty()) {
            logger.warn(RED +"The banner MOTD is on but no banner loaded from {}; every ping will use the fallback.",
                    dataDirectory);
        }

        // The config is passed as a supplier so a later reload is picked up
        // without re-registering the listener.
        proxy.getEventManager().register(this,
                new MotdPingListener(() -> configManager.config().motd(), motdLibrary));

        if (motd.mode() == MotdMode.ROLLING && motdLibrary.size() > 1 && motd.rollingIntervalSeconds() > 0L) {
            proxy.getScheduler().buildTask(this, motdLibrary::advance)
                    .repeat(motd.rollingIntervalSeconds(), TimeUnit.SECONDS)
                    .schedule();
            logger.info("Serving {} banner MOTD(s), rotating every {}s.",
                    motdLibrary.size(), motd.rollingIntervalSeconds());
        } else {
            logger.info("Serving {} banner MOTD(s) to clients on protocol {}-{}.",
                    motdLibrary.size(), motd.minProtocol(), motd.maxProtocol());
        }
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
