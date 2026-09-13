package me.ganamaga.katharsis_proxy.player;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each player's language in memory so rendering a message never touches
 * the database.
 *
 * <p>An entry is pinned while the player is online. On disconnect it is given an
 * expiry {@value #RETENTION_MINUTES} minutes out, so a quick reconnect still
 * hits a warm cache; {@link #evictExpired()} reclaims the entries afterwards.
 * Lookups honour the expiry exactly, so a late sweep only delays reclaiming
 * memory, never returns stale data.</p>
 */
public final class PlayerLanguageCache {

    private static final long RETENTION_MINUTES = 10L;

    /** How long an entry is kept after the player disconnects. */
    public static final Duration RETENTION = Duration.ofMinutes(RETENTION_MINUTES);

    /** Marks an entry belonging to an online player, which never expires. */
    private static final long ONLINE = Long.MIN_VALUE;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Duration retention;

    public PlayerLanguageCache() {
        this(RETENTION);
    }

    PlayerLanguageCache(Duration retention) {
        this.retention = retention;
    }

    /** Stores the language of a player who is now online, clearing any pending expiry. */
    public void put(UUID playerId, String language) {
        entries.put(playerId, new Entry(language, ONLINE));
    }

    /**
     * Returns the cached language, or empty when the player is unknown or their
     * entry has expired.
     */
    public Optional<String> get(UUID playerId) {
        Entry entry = entries.get(playerId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.hasExpired()) {
            entries.remove(playerId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.language());
    }

    /**
     * Starts the retention window for a player who just disconnected. Does
     * nothing when the player is not cached.
     */
    public void expireLater(UUID playerId) {
        entries.computeIfPresent(playerId,
                (id, entry) -> new Entry(entry.language(), System.nanoTime() + retention.toNanos()));
    }

    /**
     * Drops every entry whose retention window has passed.
     *
     * @return how many entries were removed
     */
    public int evictExpired() {
        int before = entries.size();
        entries.values().removeIf(Entry::hasExpired);
        return before - entries.size();
    }

    /** Number of entries currently held, expired-but-not-yet-swept included. */
    public int size() {
        return entries.size();
    }

    /** Forgets everything, e.g. on shutdown. */
    public void clear() {
        entries.clear();
    }

    /**
     * A cached language and when it may be dropped, as a {@link System#nanoTime()}
     * reading so it is immune to system clock changes.
     */
    private record Entry(String language, long expiresAtNanos) {

        boolean hasExpired() {
            return expiresAtNanos != ONLINE && System.nanoTime() - expiresAtNanos >= 0L;
        }
    }
}
