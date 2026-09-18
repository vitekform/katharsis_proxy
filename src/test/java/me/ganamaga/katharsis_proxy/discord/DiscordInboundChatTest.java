package me.ganamaga.katharsis_proxy.discord;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.messaging.CompanionRegistry;
import me.ganamaga.katharsis_proxy.player.PlayerDiscordChatCache;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordInboundChatTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordInboundChatTest.class);

    /** Backend name to the names of the players on it. */
    private final Map<String, List<String>> backends = new LinkedHashMap<>();

    /** Player name to the plain text of what they were sent. */
    private final List<Map.Entry<String, String>> received = new CopyOnWriteArrayList<>();

    private CompanionRegistry companions;
    private PlayerDiscordChatCache discordChat;
    private DiscordInboundChat inbound;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                discord:
                  chat-channels:
                    lobby: 111
                    lobby-2: 111
                    lifesteal: 222
                """);
        ConfigManager config = new ConfigManager(LOGGER, directory);
        config.load();
        LanguageRegistry registry = new LanguageRegistry(LOGGER, directory);
        registry.load("en_US");

        backends.put("lobby", List.of("Alice", "Bob"));
        backends.put("lobby-2", List.of("Carol"));
        backends.put("lifesteal", List.of("Dave"));
        backends.put("survival", List.of("Eve"));

        companions = new CompanionRegistry(LOGGER);
        discordChat = new PlayerDiscordChatCache();
        // Everyone has Discord chat shown unless a test says otherwise.
        backends.values().stream().flatMap(List::stream).forEach(name -> discordChat.put(idOf(name), true));
        inbound = new DiscordInboundChat(LOGGER, stubProxy(), new DiscordBot(LOGGER), null,
                new Messages(registry, new PlayerLanguageCache()), companions, discordChat,
                () -> config.config().discord());
    }

    // --- stubs -------------------------------------------------------------

    private ProxyServer stubProxy() {
        return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAllServers" -> {
                        List<RegisteredServer> servers = new ArrayList<>();
                        backends.forEach((name, players) -> servers.add(stubServer(name, players)));
                        yield servers;
                    }
                    case "toString" -> "StubProxy";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> null;
                });
    }

    private RegisteredServer stubServer(String name, List<String> players) {
        return (RegisteredServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RegisteredServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
                    case "getPlayersConnected" -> players.stream().map(this::stubPlayer).toList();
                    case "toString" -> "StubServer[" + name + "]";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> name.hashCode();
                    default -> null;
                });
    }

    private static UUID idOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    private Player stubPlayer(String name) {
        UUID id = idOf(name);
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName()) && args[0] instanceof Component message) {
                        received.add(Map.entry(name, PlainTextComponentSerializer.plainText().serialize(message)));
                    }
                    return switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getUsername" -> name;
                        case "toString" -> "StubPlayer[" + name + "]";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> id.hashCode();
                        default -> null;
                    };
                });
    }

    private List<String> recipients() {
        return received.stream().map(Map.Entry::getKey).sorted().toList();
    }

    // --- routing -----------------------------------------------------------

    @Test
    @DisplayName("a channel belongs to every server id mapped to it")
    void channelToServerIds() {
        assertEquals(Set.of("lobby", "lobby-2"), inbound.serverIdsFor(111L));
        assertEquals(Set.of("lifesteal"), inbound.serverIdsFor(222L));
        assertEquals(Set.of(), inbound.serverIdsFor(333L), "not a chat channel");
    }

    @Test
    @DisplayName("a message reaches every player on the channel's servers, and nobody else")
    void deliversToThoseServersOnly() {
        int sent = inbound.deliver(inbound.serverIdsFor(111L), "Notch", "hello");

        assertEquals(3, sent);
        assertEquals(List.of("Alice", "Bob", "Carol"), recipients());
        assertEquals("[Discord] Notch: hello", received.getFirst().getValue());
    }

    @Test
    @DisplayName("a backend that reports a custom server id gets that id's messages instead of its name's")
    void followsReportedServerIds() {
        companions.recordServerId("survival", "lifesteal");
        companions.recordServerId("lobby-2", "elsewhere");

        inbound.deliver(inbound.serverIdsFor(222L), "Notch", "hi");
        assertEquals(List.of("Dave", "Eve"), recipients());

        received.clear();
        inbound.deliver(inbound.serverIdsFor(111L), "Notch", "hi");
        assertEquals(List.of("Alice", "Bob"), recipients(), "lobby-2 now reports as another id");
    }

    @Test
    @DisplayName("players who have not turned Discord chat on do not get the message")
    void skipsPlayersWithDiscordChatHidden() {
        discordChat.clear();
        discordChat.put(idOf("Alice"), true);
        discordChat.put(idOf("Bob"), false);
        // Carol has no entry at all, as when her setting could not be loaded.

        int sent = inbound.deliver(inbound.serverIdsFor(111L), "Notch", "hello");

        assertEquals(1, sent);
        assertEquals(List.of("Alice"), recipients());
    }

    @Test
    @DisplayName("formatting tags in a Discord message are shown, never applied")
    void contentIsNotParsed() {
        inbound.deliver(Set.of("lifesteal"), "<red>Notch", "<bold>hi</bold> <click:run_command:/op me>x");

        assertEquals("[Discord] <red>Notch: <bold>hi</bold> <click:run_command:/op me>x",
                received.getFirst().getValue());
    }

    @Test
    @DisplayName("nothing listens while the bot is off")
    void registerWithoutBot() {
        assertFalse(new DiscordInboundChat(LOGGER, stubProxy(), new DiscordBot(LOGGER), null, null, companions,
                discordChat, DiscordConfig::new).register());
    }

    // --- content -----------------------------------------------------------

    @Test
    @DisplayName("mentions and custom emoji read as names")
    void mentionsAndEmoji() {
        assertEquals("hi @Steve and @unknown in #channel, @role :wave: :dance:",
                DiscordInboundChat.plainContent("hi <@42> and <@!7> in <#99>, <@&5> <:wave:123> <a:dance:456>",
                        Map.of(42L, "Steve"), List.of()));
    }

    @Test
    @DisplayName("attachments and stickers are named, so an image-only message still shows")
    void attachments() {
        assertEquals("look [cat.png] [Wave]",
                DiscordInboundChat.plainContent("look", Map.of(), List.of("cat.png", "Wave")));
        assertEquals("[cat.png]", DiscordInboundChat.plainContent("", Map.of(), List.of("cat.png")));
        assertEquals("", DiscordInboundChat.plainContent("  \n ", Map.of(), List.of()), "nothing to show");
    }

    @Test
    @DisplayName("line breaks become spaces and long messages are cut")
    void flattensAndTruncates() {
        assertEquals("one two three", DiscordInboundChat.plainContent("one\ntwo\r\n\nthree", Map.of(), List.of()));

        String cut = DiscordInboundChat.plainContent("👋".repeat(500), Map.of(), List.of());
        assertTrue(cut.length() <= DiscordInboundChat.CONTENT_MAX_LENGTH);
        assertTrue(cut.endsWith("👋…"));
    }
}
