package me.ganamaga.katharsis_proxy.punish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRouterTest {

    private static final List<String> ORDER = List.of("lobby", "lobby2", "lifesteal");

    @Test
    @DisplayName("the first server in the try list that is neither excluded nor banned")
    void firstAllowed() {
        assertEquals("lobby2", ServerRouter.firstAllowed(ORDER, List.of("LOBBY"), s -> false).orElseThrow());
        assertEquals("lifesteal",
                ServerRouter.firstAllowed(ORDER, List.of("lobby"), Set.of("lobby2")::contains).orElseThrow());
        assertEquals("lobby", ServerRouter.firstAllowed(ORDER, List.of(), s -> false).orElseThrow());
    }

    @Test
    @DisplayName("nothing when every server is closed")
    void none() {
        assertTrue(ServerRouter.firstAllowed(ORDER, List.of("lobby"), s -> true).isEmpty());
        assertTrue(ServerRouter.firstAllowed(List.of(), List.of(), s -> false).isEmpty());
    }
}
