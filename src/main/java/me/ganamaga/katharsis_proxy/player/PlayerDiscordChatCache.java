package me.ganamaga.katharsis_proxy.player;

import java.time.Duration;
import java.util.UUID;

/**
 * Keeps whether each player sees the Discord chat channels in game, so
 * delivering a Discord message never touches the database. Entries are kept as
 * long as {@link PlayerLanguageCache}'s after the player disconnects.
 */
public final class PlayerDiscordChatCache extends PlayerCache<Boolean> {

    public PlayerDiscordChatCache() {
        this(PlayerLanguageCache.RETENTION);
    }

    PlayerDiscordChatCache(Duration retention) {
        super(retention);
    }

    /**
     * Whether Discord chat is shown to {@code playerId}. Hidden unless they
     * turned it on, so a player whose setting could not be loaded is never
     * shown it by accident.
     */
    public boolean isShown(UUID playerId) {
        return get(playerId).orElse(false);
    }
}
