package me.ganamaga.katharsis_proxy.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.entity.Player;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes sure every player who successfully joins the proxy has a row in the
 * database, and refreshes their cached language from it on each join.
 *
 * <p>A player whose row is created here is new to the network: they get the
 * default language, and {@link FirstJoinMenuListener} opens the language
 * selector for them once they reach a server.</p>
 */
public final class PlayerJoinListener {

    private final Logger logger;
    private final LanguageRegistry registry;
    private final DatabaseManager database;
    private final PlayerRepository players;
    private final PlayerLanguageCache cache;
    private final FirstJoinMenuListener firstJoinMenu;

    public PlayerJoinListener(Logger logger, LanguageRegistry registry, DatabaseManager database,
                              PlayerRepository players, PlayerLanguageCache cache,
                              FirstJoinMenuListener firstJoinMenu) {
        this.logger = logger;
        this.registry = registry;
        this.database = database;
        this.players = players;
        this.cache = cache;
        this.firstJoinMenu = firstJoinMenu;
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String username = event.getPlayer().getUsername();

        if (!database.isAvailable()) {
            cache.put(playerId, registry.defaultLanguage());
            return null;
        }

        // Off the event thread: the login must not wait on the database.
        return EventTask.async(() -> cache.put(playerId, loadLanguage(playerId, username)));
    }

    /**
     * Reads the player's stored language, creating their row on a first join.
     * Falls back to the default language if the database cannot be reached, so
     * a player is never left without messages.
     */
    private String loadLanguage(UUID playerId, String username) {
        String defaultLanguage = registry.defaultLanguage();
        try {
            Optional<Player> stored = players.find(playerId);
            if (stored.isPresent()) {
                return stored.get().language();
            }
            if (players.createIfAbsent(playerId, defaultLanguage)) {
                logger.info("Created a player entity for {} ({}) with language '{}'.",
                        username, playerId, defaultLanguage);
                firstJoinMenu.markFirstJoin(playerId);
            }
            return defaultLanguage;
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Could not load the player entity for {} ({}); using '{}'.",
                    username, playerId, defaultLanguage, e);
            return defaultLanguage;
        }
    }
}
