package me.ganamaga.katharsis_proxy.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.entity.Player;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.player.PlayerDiscordChatCache;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes sure every player who successfully joins the proxy has a row in the
 * database, and refreshes their cached language and Discord chat setting from
 * it on each join.
 *
 * <p>A player whose row is created here is new to the network: they get the
 * default language and Discord chat hidden, and {@link FirstJoinMenuListener}
 * opens the language selector for them once they reach a server; the companion
 * asks about Discord chat right after.</p>
 */
public final class PlayerJoinListener {

    private final Logger logger;
    private final LanguageRegistry registry;
    private final DatabaseManager database;
    private final PlayerRepository players;
    private final PlayerLanguageCache cache;
    private final PlayerDiscordChatCache discordChat;
    private final FirstJoinMenuListener firstJoinMenu;

    public PlayerJoinListener(Logger logger, LanguageRegistry registry, DatabaseManager database,
                              PlayerRepository players, PlayerLanguageCache cache,
                              PlayerDiscordChatCache discordChat, FirstJoinMenuListener firstJoinMenu) {
        this.logger = logger;
        this.registry = registry;
        this.database = database;
        this.players = players;
        this.cache = cache;
        this.discordChat = discordChat;
        this.firstJoinMenu = firstJoinMenu;
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String username = event.getPlayer().getUsername();

        if (!database.isAvailable()) {
            remember(new Player(playerId, registry.defaultLanguage()));
            return null;
        }

        // Off the event thread: the login must not wait on the database.
        return EventTask.async(() -> remember(load(playerId, username)));
    }

    private void remember(Player player) {
        cache.put(player.uuid(), player.language());
        discordChat.put(player.uuid(), player.discordChat());
    }

    /**
     * Reads the player's stored settings, creating their row on a first join.
     * Falls back to the defaults if the database cannot be reached, so a player
     * is never left without messages.
     */
    private Player load(UUID playerId, String username) {
        Player defaults = new Player(playerId, registry.defaultLanguage());
        try {
            Optional<Player> stored = players.find(playerId);
            if (stored.isPresent()) {
                return stored.get();
            }
            if (players.createIfAbsent(playerId, defaults.language())) {
                logger.info("Created a player entity for {} ({}) with language '{}'.",
                        username, playerId, defaults.language());
                firstJoinMenu.markFirstJoin(playerId);
            }
            return defaults;
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Could not load the player entity for {} ({}); using '{}'.",
                    username, playerId, defaults.language(), e);
            return defaults;
        }
    }
}
