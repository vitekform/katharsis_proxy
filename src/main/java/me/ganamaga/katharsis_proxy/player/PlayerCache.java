package me.ganamaga.katharsis_proxy.player;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a value per player in memory while they are online, and for a while
 * after they leave.
 *
 * <p>An entry is pinned while the player is online. On disconnect it is given an
 * expiry the retention window out, so a quick reconnect still hits a warm
 * cache; {@link #evictExpired()} reclaims the entries afterwards. Lookups honour
 * the expiry exactly, so a late sweep only delays reclaiming memory, never
 * returns stale data.</p>
 *
 * @param <V> the cached value
 */
public class PlayerCache<V> {

    /** Marks an entry belonging to an online player, which never expires. */
    private static final long ONLINE = Long.MIN_VALUE;

    private final Map<UUID, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration retention;

    /** @param retention how long an entry is kept after the player disconnects */
    public PlayerCache(Duration retention) {
        this.retention = retention;
    }

    /** Stores the value for a player who is now online, clearing any pending expiry. */
    public void put(UUID playerId, V value) {
        entries.put(playerId, new Entry<>(value, ONLINE));
    }

    /**
     * Returns the cached value, or empty when the player is unknown or their
     * entry has expired.
     */
    public Optional<V> get(UUID playerId) {
        Entry<V> entry = entries.get(playerId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.hasExpired()) {
            entries.remove(playerId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /**
     * Starts the retention window for a player who just disconnected. Does
     * nothing when the player is not cached.
     */
    public void expireLater(UUID playerId) {
        entries.computeIfPresent(playerId,
                (id, entry) -> new Entry<>(entry.value(), System.nanoTime() + retention.toNanos()));
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
     * A cached value and when it may be dropped, as a {@link System#nanoTime()}
     * reading so it is immune to system clock changes.
     */
    private record Entry<V>(V value, long expiresAtNanos) {

        boolean hasExpired() {
            return expiresAtNanos != ONLINE && System.nanoTime() - expiresAtNanos >= 0L;
        }
    }
}
