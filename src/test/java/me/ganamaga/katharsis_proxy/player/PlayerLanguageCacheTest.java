package me.ganamaga.katharsis_proxy.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLanguageCacheTest {

    /** Short window so the retention behaviour is testable in milliseconds. */
    private static final Duration RETENTION = Duration.ofMillis(300);

    @Test
    @DisplayName("the default retention is the documented ten minutes")
    void documentedRetention() {
        assertEquals(10L, PlayerLanguageCache.RETENTION.toMinutes());
    }

    @Test
    @DisplayName("an online entry is never dropped")
    void onlineEntriesArePinned() throws InterruptedException {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        UUID id = UUID.randomUUID();

        assertFalse(cache.get(id).isPresent(), "unknown player");
        cache.put(id, "cs_CZ");

        Thread.sleep(RETENTION.toMillis() * 2);
        assertEquals("cs_CZ", cache.get(id).orElse(null), "still online, so still cached");
        assertEquals(0, cache.evictExpired(), "sweep must not touch an online entry");
    }

    @Test
    @DisplayName("a disconnected entry survives the window, then disappears")
    void retentionWindow() throws InterruptedException {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        UUID id = UUID.randomUUID();
        cache.put(id, "cs_CZ");

        cache.expireLater(id);
        assertEquals("cs_CZ", cache.get(id).orElse(null), "still warm right after disconnect");
        assertEquals(0, cache.evictExpired(), "not expired yet");

        Thread.sleep(RETENTION.toMillis() + 150);
        assertFalse(cache.get(id).isPresent(), "lookup must miss once the window has passed");
    }

    @Test
    @DisplayName("rejoining inside the window cancels the pending expiry")
    void rejoinCancelsExpiry() throws InterruptedException {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        UUID id = UUID.randomUUID();

        cache.put(id, "en_US");
        cache.expireLater(id);
        Thread.sleep(150);

        cache.put(id, "cs_CZ");                       // rejoined
        Thread.sleep(RETENTION.toMillis());           // past the original expiry

        assertEquals("cs_CZ", cache.get(id).orElse(null), "rejoin should have re-pinned the entry");
        assertEquals(0, cache.evictExpired());
    }

    @Test
    @DisplayName("the sweep reclaims expired entries")
    void sweepReclaimsMemory() throws InterruptedException {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        for (int i = 0; i < 50; i++) {
            UUID id = UUID.randomUUID();
            cache.put(id, "en_US");
            cache.expireLater(id);
        }
        assertEquals(50, cache.size());

        Thread.sleep(RETENTION.toMillis() + 150);
        assertEquals(50, cache.evictExpired());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("a lookup after expiry removes the entry itself")
    void lookupEvicts() throws InterruptedException {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        UUID id = UUID.randomUUID();
        cache.put(id, "en_US");
        cache.expireLater(id);

        Thread.sleep(RETENTION.toMillis() + 150);
        assertFalse(cache.get(id).isPresent());
        assertEquals(0, cache.size(), "the miss should have dropped the entry");
    }

    @Test
    @DisplayName("expiring an unknown player creates nothing")
    void expireUnknownIsNoOp() {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        cache.expireLater(UUID.randomUUID());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("clear forgets everything")
    void clearForgetsEverything() {
        PlayerLanguageCache cache = new PlayerLanguageCache(RETENTION);
        cache.put(UUID.randomUUID(), "en_US");
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.get(UUID.randomUUID()).isEmpty());
    }
}
