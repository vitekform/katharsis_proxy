package me.ganamaga.katharsis_proxy.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginServersTest {

    private static final Map<String, List<String>> FORCED = Map.of(
            "modded.kathars.eu", List.of("modded", "lobby"),
            "empty.kathars.eu", List.of());
    private static final List<String> TRY = List.of("lobby", "survival");

    private static Optional<InetSocketAddress> host(String name) {
        return Optional.of(InetSocketAddress.createUnresolved(name, 25565));
    }

    @Test
    @DisplayName("the forced host for the typed address picks the first server")
    void forcedHostWins() {
        assertEquals(Optional.of("modded"), LoginServers.firstServer(host("modded.kathars.eu"), FORCED, TRY));
        assertEquals(Optional.of("modded"), LoginServers.firstServer(host("MODDED.Kathars.EU"), FORCED, TRY));
    }

    @Test
    @DisplayName("without a forced host, or with an empty one, the try list does")
    void tryListOtherwise() {
        assertEquals(Optional.of("lobby"), LoginServers.firstServer(host("play.kathars.eu"), FORCED, TRY));
        assertEquals(Optional.of("lobby"), LoginServers.firstServer(host("empty.kathars.eu"), FORCED, TRY));
        assertEquals(Optional.of("lobby"), LoginServers.firstServer(Optional.empty(), FORCED, TRY));
        assertEquals(Optional.empty(), LoginServers.firstServer(Optional.empty(), FORCED, List.of()));
    }

    @Test
    @DisplayName("only players sent to a listed server skip the void, whatever the case")
    void onlyListedServers() {
        LoginServers servers = new LoginServers(List.of(" Modded ", ""));
        assertTrue(servers.isFirstServerFor(host("modded.kathars.eu"), FORCED, TRY));
        assertFalse(servers.isFirstServerFor(host("play.kathars.eu"), FORCED, TRY));
        assertTrue(servers.contains("MODDED"));
        assertFalse(servers.contains("lobby"));
        assertFalse(servers.isEmpty());
        assertTrue(new LoginServers(List.of()).isEmpty());
    }
}
