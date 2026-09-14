package me.ganamaga.katharsis_proxy.motd;

import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.server.ServerPing;
import me.ganamaga.katharsis_proxy.config.MotdConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives real {@link ProxyPingEvent}s through the listener, with a stand-in for
 * the inbound connection so the client's protocol version can be chosen.
 */
class MotdPingListenerTest {

    private static final String ORIGINAL_DESCRIPTION = "from velocity.toml";
    private static final String ORIGINAL_VERSION_NAME = "Katharsis";

    private static MotdConfig config(String yaml) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .build()
                .load()
                .get(MotdConfig.class, new MotdConfig());
    }

    private static InboundConnection connectionOn(ProtocolVersion version) {
        return (InboundConnection) Proxy.newProxyInstance(
                MotdPingListenerTest.class.getClassLoader(),
                new Class<?>[]{InboundConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProtocolVersion" -> version;
                    case "toString" -> "InboundConnection(" + version + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static ServerPing incomingPing() {
        return ServerPing.builder()
                .version(new ServerPing.Version(
                        ProtocolVersion.MINECRAFT_1_21_9.getProtocol(), ORIGINAL_VERSION_NAME))
                .onlinePlayers(3)
                .maximumPlayers(20)
                .description(Component.text(ORIGINAL_DESCRIPTION))
                .build();
    }

    private MotdLibrary libraryWith(Path directory, String... texts) throws IOException {
        for (int i = 0; i < texts.length; i++) {
            String name = i == 0 ? "motd.json" : "motd" + (i + 1) + ".json";
            Files.writeString(directory.resolve(name), "{\"text\":\"" + texts[i] + "\"}");
        }
        MotdLibrary library = new MotdLibrary(
                LoggerFactory.getLogger(MotdPingListenerTest.class), directory);
        library.load(texts.length);
        return library;
    }

    /** Runs one ping through the listener and hands back the answer. */
    private ServerPing ping(MotdConfig motd, MotdLibrary library, ProtocolVersion version) {
        ProxyPingEvent event = new ProxyPingEvent(connectionOn(version), incomingPing());
        new MotdPingListener(() -> motd, library).onProxyPing(event);
        return event.getPing();
    }

    private String description(ServerPing answer) {
        return PlainTextComponentSerializer.plainText().serialize(answer.getDescriptionComponent());
    }

    @Test
    @DisplayName("a banner-capable client is served the banner")
    void servesBannerToSupportedClient(@TempDir Path directory) throws Exception {
        ServerPing answer = ping(config("enabled: true\n"), libraryWith(directory, "banner"),
                ProtocolVersion.MINECRAFT_1_21_9);

        assertEquals("banner", description(answer));
    }

    @Test
    @DisplayName("an older client is served readable text instead of broken glyphs")
    void servesFallbackToOldClient(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                fallback-motd: "&cToo old"
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_7);

        assertEquals("Too old", description(answer), "the & code is parsed, not shown literally");
    }

    @Test
    @DisplayName("with no banner loaded even a supported client gets the fallback")
    void fallsBackWhenNothingLoaded(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                fallback-motd: "nothing here"
                """);

        ServerPing answer = ping(motd, libraryWith(directory), ProtocolVersion.MINECRAFT_1_21_9);

        assertEquals("nothing here", description(answer));
    }

    @Test
    @DisplayName("fallback: proxy leaves the velocity.toml description alone")
    void proxyFallbackKeepsTheOriginalDescription(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                fallback: proxy
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_7);

        assertEquals(ORIGINAL_DESCRIPTION, description(answer));
    }

    @Test
    @DisplayName("while disabled the ping is passed through untouched")
    void disabledLeavesThePingAlone(@TempDir Path directory) throws Exception {
        ServerPing answer = ping(config("enabled: false\n"), libraryWith(directory, "banner"),
                ProtocolVersion.MINECRAFT_1_21_9);

        assertEquals(ORIGINAL_DESCRIPTION, description(answer));
        assertEquals(3, answer.getPlayers().orElseThrow().getOnline());
        assertEquals(ORIGINAL_VERSION_NAME, answer.getVersion().getName());
    }

    @Test
    @DisplayName("the version override translates & codes into the section codes clients render")
    void overridesVersionMessage(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                override-version-message: true
                custom-version-message: "&cMaintenance"
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_9);

        assertEquals("§cMaintenance", answer.getVersion().getName());
        assertEquals(ProtocolVersion.MINECRAFT_1_21_9.getProtocol(), answer.getVersion().getProtocol(),
                "the protocol is untouched unless always-out-of-date is on");
    }

    @Test
    @DisplayName("always-out-of-date advertises a protocol the client does not share")
    void alwaysOutOfDate(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                always-out-of-date: true
                out-of-date-protocol: 47
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_9);

        assertEquals(47, answer.getVersion().getProtocol());
        assertEquals(ORIGINAL_VERSION_NAME, answer.getVersion().getName(), "the name is left alone");
    }

    @Test
    @DisplayName("the version line is left alone when neither override is on")
    void versionUntouchedByDefault(@TempDir Path directory) throws Exception {
        ServerPing answer = ping(config("enabled: true\n"), libraryWith(directory, "banner"),
                ProtocolVersion.MINECRAFT_1_21_9);

        assertEquals(ORIGINAL_VERSION_NAME, answer.getVersion().getName());
        assertEquals(ProtocolVersion.MINECRAFT_1_21_9.getProtocol(), answer.getVersion().getProtocol());
    }

    @Test
    @DisplayName("hiding the player count removes it from the answer entirely")
    void hidesPlayerCount(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                hide-player-count: true
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_9);

        assertTrue(answer.getPlayers().isEmpty());
    }

    @Test
    @DisplayName("the fake player count replaces the real one")
    void fakesPlayerCount(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                fake-player-count: true
                fake-player-percentage: 50
                max-players: 200
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_9);

        ServerPing.Players players = answer.getPlayers().orElseThrow();
        assertEquals(200, players.getMax());
        assertEquals(100, players.getOnline());
    }

    @Test
    @DisplayName("hiding the count wins over faking it")
    void hideTakesPrecedenceOverFake(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                hide-player-count: true
                fake-player-count: true
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_9);

        assertTrue(answer.getPlayers().isEmpty());
    }

    @Test
    @DisplayName("the overrides also apply to clients that only get the fallback")
    void overridesApplyToFallbackClients(@TempDir Path directory) throws Exception {
        MotdConfig motd = config("""
                enabled: true
                fallback-motd: "too old"
                hide-player-count: true
                override-version-message: true
                custom-version-message: "&cMaintenance"
                """);

        ServerPing answer = ping(motd, libraryWith(directory, "banner"), ProtocolVersion.MINECRAFT_1_21_7);

        assertEquals("too old", description(answer));
        assertTrue(answer.getPlayers().isEmpty(), "not just a modern-client feature");
        assertEquals("§cMaintenance", answer.getVersion().getName());
    }

    @Test
    @DisplayName("a config change is picked up without re-registering the listener")
    void readsConfigPerPing(@TempDir Path directory) throws Exception {
        MotdLibrary library = libraryWith(directory, "banner");
        MotdConfig[] current = {config("enabled: false\n")};
        MotdPingListener listener = new MotdPingListener(() -> current[0], library);

        ProxyPingEvent first = new ProxyPingEvent(
                connectionOn(ProtocolVersion.MINECRAFT_1_21_9), incomingPing());
        listener.onProxyPing(first);
        assertEquals(ORIGINAL_DESCRIPTION, description(first.getPing()));

        current[0] = config("enabled: true\n");

        ProxyPingEvent second = new ProxyPingEvent(
                connectionOn(ProtocolVersion.MINECRAFT_1_21_9), incomingPing());
        listener.onProxyPing(second);
        assertEquals("banner", description(second.getPing()));
        assertFalse(description(second.getPing()).equals(ORIGINAL_DESCRIPTION));
    }
}
