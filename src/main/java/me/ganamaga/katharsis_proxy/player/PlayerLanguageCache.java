package me.ganamaga.katharsis_proxy.player;

import java.time.Duration;

/**
 * Keeps each player's language in memory so rendering a message never touches
 * the database. Entries are kept {@value #RETENTION_MINUTES} minutes after the
 * player disconnects.
 */
public final class PlayerLanguageCache extends PlayerCache<String> {

    private static final long RETENTION_MINUTES = 10L;

    /** How long an entry is kept after the player disconnects. */
    public static final Duration RETENTION = Duration.ofMinutes(RETENTION_MINUTES);

    public PlayerLanguageCache() {
        this(RETENTION);
    }

    PlayerLanguageCache(Duration retention) {
        super(retention);
    }
}
