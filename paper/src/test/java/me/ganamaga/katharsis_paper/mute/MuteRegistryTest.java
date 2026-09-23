package me.ganamaga.katharsis_paper.mute;

import me.ganamaga.katharsis_paper.protocol.PunishChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteRegistryTest {

    private final MuteRegistry registry = new MuteRegistry();
    private final UUID player = UUID.randomUUID();

    @Test
    @DisplayName("a mute holds until its end, without the proxy having to say so")
    void expiresOnItsOwn() {
        registry.update(player, new MuteState(1_000L, 1, List.of()));
        assertTrue(registry.activeMute(player, 999L).isPresent());
        assertTrue(registry.activeMute(player, 1_000L).isEmpty());
    }

    @Test
    @DisplayName("a permanent mute holds until lifted; lifting or leaving forgets it")
    void permanentUntilLifted() {
        registry.update(player, new MuteState(PunishChannel.PERMANENT, 1, List.of()));
        assertTrue(registry.activeMute(player, Long.MAX_VALUE).isPresent());
        registry.update(player, null);
        assertTrue(registry.activeMute(player, 0L).isEmpty());

        registry.update(player, new MuteState(PunishChannel.PERMANENT, 1, List.of()));
        registry.forget(player);
        assertTrue(registry.activeMute(player, 0L).isEmpty());
    }

    @Test
    @DisplayName("blocked commands match by label, with or without a slash or namespace, and nothing else")
    void blocks() {
        MuteState state = new MuteState(PunishChannel.PERMANENT, 1, List.of("msg", "tell"));
        assertTrue(state.blocks("/msg Notch hi"));
        assertTrue(state.blocks("/MSG Notch hi"));
        assertTrue(state.blocks("/minecraft:tell Notch hi"));
        assertTrue(state.blocks("/msg"));
        assertFalse(state.blocks("/msgs"));
        assertFalse(state.blocks("/spawn msg"));
        assertFalse(state.blocks("/home"));
    }
}
