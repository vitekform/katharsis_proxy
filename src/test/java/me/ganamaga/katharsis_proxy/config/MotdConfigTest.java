package me.ganamaga.katharsis_proxy.config;

import com.velocitypowered.api.network.ProtocolVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotdConfigTest {

    /** Reads a config.yml fragment the same way {@link ConfigManager} would. */
    private static MotdConfig parse(String yaml) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .build()
                .load()
                .get(MotdConfig.class, new MotdConfig());
    }

    @Test
    @DisplayName("stays off until it is turned on, so velocity.toml keeps the ping")
    void defaultsToOff() {
        MotdConfig motd = new MotdConfig();

        assertFalse(motd.enabled());
        assertEquals(MotdMode.RANDOM, motd.mode());
        assertEquals(MotdFallback.CONFIG, motd.fallback());
        assertEquals(1, motd.count());
        assertFalse(motd.rewritesVersion());
    }

    @Test
    @DisplayName("the default protocol range is exactly 1.21.9 to 1.21.11")
    void defaultRangeCoversBannerCapableClients() {
        MotdConfig motd = new MotdConfig();

        assertEquals(ProtocolVersion.MINECRAFT_1_21_9.getProtocol(), motd.minProtocol());
        assertEquals(ProtocolVersion.MINECRAFT_1_21_11.getProtocol(), motd.maxProtocol());

        assertFalse(motd.supportsBanner(ProtocolVersion.MINECRAFT_1_21_7.getProtocol()), "too old");
        assertTrue(motd.supportsBanner(ProtocolVersion.MINECRAFT_1_21_9.getProtocol()));
        assertTrue(motd.supportsBanner(ProtocolVersion.MINECRAFT_1_21_11.getProtocol()));
        assertFalse(motd.supportsBanner(ProtocolVersion.MINECRAFT_26_1.getProtocol()), "too new");
    }

    @Test
    @DisplayName("kebab-case keys map onto the fields")
    void readsOverrides() throws ConfigurateException {
        MotdConfig motd = parse("""
                enabled: true
                mode: rolling
                count: 3
                rolling-interval-seconds: 15
                min-protocol: 770
                max-protocol: 776
                fallback: proxy
                override-version-message: true
                custom-version-message: "&eBack soon"
                always-out-of-date: true
                out-of-date-protocol: 47
                fake-player-count: true
                fake-player-percentage: 50
                max-players: 200
                """);

        assertTrue(motd.enabled());
        assertEquals(MotdMode.ROLLING, motd.mode());
        assertEquals(3, motd.count());
        assertEquals(15L, motd.rollingIntervalSeconds());
        assertEquals(MotdFallback.PROXY, motd.fallback());
        assertEquals("&eBack soon", motd.customVersionMessage());
        assertEquals(47, motd.outOfDateProtocol());
        assertTrue(motd.rewritesVersion());
        assertTrue(motd.supportsBanner(ProtocolVersion.MINECRAFT_1_21_5.getProtocol()));
    }

    @Test
    @DisplayName("keys left out keep their defaults")
    void partialOverrideKeepsDefaults() throws ConfigurateException {
        MotdConfig motd = parse("enabled: true\n");

        assertTrue(motd.enabled());
        assertEquals(MotdMode.RANDOM, motd.mode());
        assertEquals(773, motd.minProtocol());
        assertEquals(67, motd.maxPlayers());
    }

    @Test
    @DisplayName("the fake online count is a percentage of the advertised maximum")
    void fakeCountIsAPercentage() throws ConfigurateException {
        assertEquals(6, new MotdConfig().fakeOnlinePlayers(), "67 * 10%");
        assertEquals(100, parse("max-players: 200\nfake-player-percentage: 50\n").fakeOnlinePlayers());
    }

    @Test
    @DisplayName("a percentage outside 0-100 is clamped rather than advertised as-is")
    void fakeCountIsClamped() throws ConfigurateException {
        assertEquals(200, parse("max-players: 200\nfake-player-percentage: 500\n").fakeOnlinePlayers(),
                "never more than the maximum");
        assertEquals(0, parse("max-players: 200\nfake-player-percentage: -20\n").fakeOnlinePlayers(),
                "never negative");
    }
}
