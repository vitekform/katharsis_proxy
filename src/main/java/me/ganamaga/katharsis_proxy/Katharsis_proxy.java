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
import me.ganamaga.katharsis_proxy.command.LanguageCommand;
import me.ganamaga.katharsis_proxy.command.ServerSwitchCommand;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.listener.PlayerJoinListener;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannel;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannelListener;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Katharsis_proxy {

    /** Every server switch command shares this cooldown. */
    private static final Duration COMMAND_COOLDOWN = Duration.ofMinutes(1);

    /** How often expired language cache entries are reclaimed. */
    private static final long CACHE_SWEEP_MINUTES = 1L;

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

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("=======================================");
        logger.info("  Katharsis Proxy");
        logger.info("  Loading...");
        logger.info("=======================================");

        loadConfig();
        loadLanguages();
        setUpDatabase();
        registerCommands();
        registerPluginMessaging();
        scheduleCacheSweep();

        logger.info("Katharsis Proxy loaded successfully.");
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
            logger.error("Could not load config.yml; continuing with the built-in defaults.", e);
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
            logger.error("Could not load the language files; messages will show their keys.", e);
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
            logger.error("Could not open the database; player data will not be stored.", e);
            database.close();
            return;
        }

        proxy.getEventManager().register(this,
                new PlayerJoinListener(logger, languageRegistry, database, players, languageCache));
        logger.info("Player entities will be created on first join.");
    }

    private void registerCommands() {
        registerServerSwitchCommand("lobby");
        registerServerSwitchCommand("lifesteal");
        logger.info("Registered {} server switch command(s) with a {}s cooldown.",
                serverSwitchCommands.size(), COMMAND_COOLDOWN.toSeconds());

        CommandManager commandManager = proxy.getCommandManager();
        commandManager.register(
                commandManager.metaBuilder("language").aliases("lang").plugin(this).build(),
                new LanguageCommand(proxy, this, logger, messages, players, languageCache));
        logger.info("Registered /language.");
    }

    /**
     * Opens the language channel to backend servers. Registered even when the
     * database is down, so callers get an explicit error instead of silence.
     */
    private void registerPluginMessaging() {
        proxy.getChannelRegistrar().register(LanguageChannel.IDENTIFIER);
        proxy.getEventManager().register(this, new LanguageChannelListener(
                proxy, logger, languageRegistry, database, players, languageCache));
        logger.info("Serving language requests on the '{}' plugin message channel.",
                LanguageChannel.IDENTIFIER.getId());
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
            logger.warn("Registered /{} but no server named '{}' exists in velocity.toml.", serverName, serverName);
        } else {
            logger.info("Registered /{} -> server '{}'.", serverName, serverName);
        }
    }
}
