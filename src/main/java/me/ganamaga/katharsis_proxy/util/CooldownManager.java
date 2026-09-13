package me.ganamaga.katharsis_proxy.util;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cooldown tracker backed by the monotonic clock, so it is immune to
 * system time changes.
 */
public final class CooldownManager {

    private final Map<UUID, Long> expiryNanos = new ConcurrentHashMap<>();
    private final long cooldownNanos;

    public CooldownManager(Duration cooldown) {
        this.cooldownNanos = cooldown.toNanos();
    }

    /**
     * Returns how much of the cooldown is left for {@code playerId}, or
     * {@link Duration#ZERO} when the player may act again.
     */
    public Duration remaining(UUID playerId) {
        Long expiry = expiryNanos.get(playerId);
        if (expiry == null) {
            return Duration.ZERO;
        }
        long left = expiry - System.nanoTime();
        if (left <= 0L) {
            expiryNanos.remove(playerId, expiry);
            return Duration.ZERO;
        }
        return Duration.ofNanos(left);
    }

    /** Starts (or restarts) the cooldown for {@code playerId}. */
    public void start(UUID playerId) {
        expiryNanos.put(playerId, System.nanoTime() + cooldownNanos);
    }

    /** Clears the cooldown for {@code playerId}, e.g. once they disconnect. */
    public void clear(UUID playerId) {
        expiryNanos.remove(playerId);
    }
}
