package me.ganamaga.katharsis_proxy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownManagerTest {

    @Test
    @DisplayName("a fresh player is off cooldown")
    void freshPlayerIsReady() {
        assertTrue(new CooldownManager(Duration.ofMinutes(1)).remaining(UUID.randomUUID()).isZero());
    }

    @Test
    @DisplayName("start puts the player on cooldown, and it elapses")
    void startAndElapse() throws InterruptedException {
        CooldownManager cooldowns = new CooldownManager(Duration.ofMillis(300));
        UUID id = UUID.randomUUID();

        cooldowns.start(id);
        assertFalse(cooldowns.remaining(id).isZero(), "should be on cooldown right after start");

        Thread.sleep(400);
        assertTrue(cooldowns.remaining(id).isZero(), "cooldown should have elapsed");
    }

    @Test
    @DisplayName("clear ends the cooldown early")
    void clearEndsCooldown() {
        CooldownManager cooldowns = new CooldownManager(Duration.ofMinutes(1));
        UUID id = UUID.randomUUID();

        cooldowns.start(id);
        cooldowns.clear(id);
        assertTrue(cooldowns.remaining(id).isZero());
    }

    @Test
    @DisplayName("cooldowns are per player")
    void cooldownsArePerPlayer() {
        CooldownManager cooldowns = new CooldownManager(Duration.ofMinutes(1));
        UUID first = UUID.randomUUID();

        cooldowns.start(first);
        assertFalse(cooldowns.remaining(first).isZero());
        assertTrue(cooldowns.remaining(UUID.randomUUID()).isZero());
    }
}
