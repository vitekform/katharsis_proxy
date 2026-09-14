package me.ganamaga.katharsis_proxy.messaging;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives real {@link PluginMessageEvent}s through the listener against an H2
 * database, with stand-ins for the proxy and the backend connection.
 */
class LanguageChannelListenerTest {

    private final List<byte[]> replies = new ArrayList<>();
    private final Set<UUID> online = new HashSet<>();

    private DatabaseManager database;
    private PlayerRepository players;
    private PlayerLanguageCache cache;
    private LanguageChannelListener listener;
    private CompanionRegistry companions;
    private ServerConnection backend;
    private final List<String> unregisteredCommands = new ArrayList<>();
    private final List<Component> sentToPlayer = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        var logger = LoggerFactory.getLogger(LanguageChannelListenerTest.class);
        ConfigManager config = new ConfigManager(logger, directory);
        config.load();
        LanguageRegistry registry = new LanguageRegistry(logger, directory);
        registry.load("en_US");

        cache = new PlayerLanguageCache();
        database = new DatabaseManager(logger, directory);
        database.start(config.config().database());
        players = new PlayerRepository(database);
        players.createTable();

        backend = stubBackend();
        ProxyServer proxy = stubProxy();
        companions = new CompanionRegistry(proxy, logger, "language");
        listener = new LanguageChannelListener(proxy, logger, registry,
                new Messages(registry, cache), database, players, cache, companions);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    // --- stubs -------------------------------------------------------------

    private ServerConnection stubBackend() {
        return (ServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendPluginMessage" -> {
                        replies.add((byte[]) args[1]);
                        yield true;
                    }
                    case "getServerInfo" -> new ServerInfo("lifesteal", new InetSocketAddress("127.0.0.1", 25566));
                    case "toString" -> "StubBackend";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> null;
                });
    }

    private ProxyServer stubProxy() {
        return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayer" -> online.contains(args[0]) ? Optional.of(stubPlayer()) : Optional.empty();
                    case "getCommandManager" -> stubCommandManager();
                    case "toString" -> "StubProxy";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 2;
                    default -> null;
                });
    }

    private CommandManager stubCommandManager() {
        return (CommandManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandManager.class}, (proxy, method, args) -> {
                    if ("unregister".equals(method.getName()) && args[0] instanceof String name) {
                        unregisteredCommands.add(name);
                    }
                    return switch (method.getName()) {
                        case "toString" -> "StubCommandManager";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> 4;
                        default -> null;
                    };
                });
    }

    private Player stubPlayer() {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName()) && args[0] instanceof Component message) {
                        sentToPlayer.add(message);
                    }
                    return switch (method.getName()) {
                        case "toString" -> "StubPlayer";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> 3;
                        default -> null;
                    };
                });
    }

    // --- protocol helpers --------------------------------------------------

    private static byte[] request(String operation, String requestId, String uuid, String language) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(operation);
        out.writeUTF(requestId);
        out.writeUTF(uuid);
        if (LanguageChannel.OP_SET.equals(operation)) {
            out.writeUTF(language);
        }
        return out.toByteArray();
    }

    /** An announce request: the fourth field carries the companion's version. */
    private static byte[] announce(String requestId, String version) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(LanguageChannel.OP_ANNOUNCE);
        out.writeUTF(requestId);
        out.writeUTF("");
        out.writeUTF(version);
        return out.toByteArray();
    }

    private record Reply(String operation, String requestId, String uuid,
                         boolean success, String language, String error, Menu menu) {
    }

    /** The extra fields a get_language_menu reply carries. */
    private record Menu(String title, String selectHint, String currentHint,
                        java.util.LinkedHashMap<String, String> entries) {
    }

    /** Delivers a message and returns the decoded reply, or null when none was sent. */
    private Reply call(ChannelMessageSource source, byte[] data) throws Exception {
        return call(source, LanguageChannel.IDENTIFIER, data).reply();
    }

    private record Delivery(Reply reply, boolean forwarded) {
    }

    private Delivery call(ChannelMessageSource source, MinecraftChannelIdentifier channel, byte[] data)
            throws Exception {
        replies.clear();
        PluginMessageEvent event = new PluginMessageEvent(source, stubBackend(), channel, data);

        EventTask task = listener.onPluginMessage(event);
        if (task != null) {
            var done = new java.util.concurrent.CountDownLatch(1);
            task.execute((Continuation) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Continuation.class}, (p, m, a) -> {
                        done.countDown();
                        return null;
                    }));
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "the async task should finish");
        }

        boolean forwarded = event.getResult().isAllowed();
        if (replies.isEmpty()) {
            return new Delivery(null, forwarded);
        }
        ByteArrayDataInput in = ByteStreams.newDataInput(replies.get(0));
        String operation = in.readUTF();
        Reply reply = new Reply(operation, in.readUTF(), in.readUTF(),
                in.readBoolean(), in.readUTF(), in.readUTF(),
                LanguageChannel.OP_MENU.equals(operation) ? readMenu(in) : null);
        return new Delivery(reply, forwarded);
    }

    private static Menu readMenu(ByteArrayDataInput in) {
        String title = in.readUTF();
        String selectHint = in.readUTF();
        String currentHint = in.readUTF();
        int count = in.readInt();
        var entries = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) {
            entries.put(in.readUTF(), in.readUTF());
        }
        return new Menu(title, selectHint, currentHint, entries);
    }

    // --- tests -------------------------------------------------------------

    @Test
    @DisplayName("get_language returns the stored language and echoes the header")
    void getLanguage() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        Reply reply = call(backend, request(LanguageChannel.OP_GET, "r1", id.toString(), null));

        assertTrue(reply.success());
        assertEquals("en_US", reply.language());
        assertEquals(LanguageChannel.OP_GET, reply.operation());
        assertEquals("r1", reply.requestId());
        assertEquals(id.toString(), reply.uuid());
        assertEquals(LanguageChannel.ERROR_NONE, reply.error());
    }

    @Test
    @DisplayName("set_language persists, caches and normalises the code")
    void setLanguage() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        Reply reply = call(backend, request(LanguageChannel.OP_SET, "r2", id.toString(), "cs_CZ"));
        assertTrue(reply.success());
        assertEquals("cs_CZ", reply.language());
        assertEquals("cs_CZ", players.find(id).orElseThrow().language());
        assertEquals("cs_CZ", cache.get(id).orElse(null));

        Reply normalised = call(backend, request(LanguageChannel.OP_SET, "r3", id.toString(), "EN-us"));
        assertEquals("en_US", normalised.language());
        assertEquals("en_US", players.find(id).orElseThrow().language());
    }

    @Test
    @DisplayName("set_language creates a row for a player who has never joined")
    void setLanguageCreatesRow() throws Exception {
        UUID id = UUID.randomUUID();

        Reply reply = call(backend, request(LanguageChannel.OP_SET, "r4", id.toString(), "cs_CZ"));

        assertTrue(reply.success());
        assertEquals("cs_CZ", players.find(id).orElseThrow().language());
    }

    @Test
    @DisplayName("clear_language resets to the configured default")
    void clearLanguage() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "cs_CZ");

        Reply reply = call(backend, request(LanguageChannel.OP_CLEAR, "r5", id.toString(), null));

        assertTrue(reply.success());
        assertEquals("en_US", reply.language());
        assertEquals("en_US", players.find(id).orElseThrow().language());
    }

    @Test
    @DisplayName("clear_language creates no row for an unknown player")
    void clearUnknownPlayerCreatesNothing() throws Exception {
        UUID id = UUID.randomUUID();

        Reply reply = call(backend, request(LanguageChannel.OP_CLEAR, "r6", id.toString(), null));

        assertTrue(reply.success());
        assertEquals("en_US", reply.language());
        assertFalse(players.find(id).isPresent(), "clearing must not materialise a row");
    }

    @Test
    @DisplayName("a language with no file is rejected and nothing is written")
    void unknownLanguageRejected() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        Reply reply = call(backend, request(LanguageChannel.OP_SET, "r7", id.toString(), "zz_ZZ"));

        assertFalse(reply.success());
        assertEquals(LanguageChannel.ERROR_UNKNOWN_LANGUAGE, reply.error());
        assertEquals("", reply.language());
        assertEquals("en_US", players.find(id).orElseThrow().language());
    }

    @Test
    @DisplayName("a malformed uuid, operation or payload each report their own error")
    void malformedRequests() throws Exception {
        assertEquals(LanguageChannel.ERROR_INVALID_UUID,
                call(backend, request(LanguageChannel.OP_GET, "r8", "not-a-uuid", null)).error());
        assertEquals(LanguageChannel.ERROR_UNKNOWN_OPERATION,
                call(backend, request("frobnicate", "r9", UUID.randomUUID().toString(), null)).error());

        Reply truncated = call(backend, new byte[]{1, 2, 3});
        assertEquals(LanguageChannel.ERROR_MALFORMED_REQUEST, truncated.error());
        assertEquals("", truncated.operation(), "nothing could be echoed back");
    }

    @Test
    @DisplayName("a player who has never joined is reported as unknown, not defaulted")
    void unknownPlayerReported() throws Exception {
        Reply reply = call(backend, request(LanguageChannel.OP_GET, "r10", UUID.randomUUID().toString(), null));

        assertFalse(reply.success());
        assertEquals(LanguageChannel.ERROR_UNKNOWN_PLAYER, reply.error());
    }

    @Test
    @DisplayName("a message from a client is ignored entirely")
    void clientMessagesAreIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        Delivery delivery = call(stubPlayer(), LanguageChannel.IDENTIFIER,
                request(LanguageChannel.OP_SET, "r11", id.toString(), "cs_CZ"));

        assertNull(delivery.reply(), "a client gets no answer");
        assertFalse(delivery.forwarded(), "and the message is still not relayed");
        assertEquals("en_US", players.find(id).orElseThrow().language(), "nothing was written");
    }

    @Test
    @DisplayName("our channel is never forwarded, other channels are untouched")
    void forwardingRules() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        Delivery ours = call(backend, LanguageChannel.IDENTIFIER,
                request(LanguageChannel.OP_GET, "r12", id.toString(), null));
        assertFalse(ours.forwarded(), "the proxy handles this channel itself");

        Delivery other = call(backend, MinecraftChannelIdentifier.create("bungeecord", "main"), new byte[]{9});
        assertTrue(other.forwarded(), "unrelated channels must pass through");
        assertNull(other.reply());
    }

    @Test
    @DisplayName("an offline lookup is cached but left to expire")
    void offlineLookupIsNotPinned() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "cs_CZ");

        call(backend, request(LanguageChannel.OP_GET, "r13", id.toString(), null));
        assertEquals("cs_CZ", cache.get(id).orElse(null), "cached for the next lookup");

        // An online player's entry is pinned instead; both are present, but only
        // the offline one carries an expiry (see PlayerLanguageCacheTest).
        cache.clear();
        online.add(id);
        call(backend, request(LanguageChannel.OP_GET, "r14", id.toString(), null));
        assertEquals("cs_CZ", cache.get(id).orElse(null));
    }

    @Test
    @DisplayName("requests are answered while the database is down")
    void databaseDownStillAnswers() throws Exception {
        database.close();

        Reply reply = call(backend, request(LanguageChannel.OP_GET, "r15", UUID.randomUUID().toString(), null));

        assertFalse(reply.success());
        assertEquals(LanguageChannel.ERROR_DATABASE_UNAVAILABLE, reply.error());
    }

    @Test
    @DisplayName("announce takes /language off the proxy, once")
    void announceTakesOverLanguageCommand() throws Exception {
        assertFalse(companions.hasCompanion());

        Reply first = call(backend, announce("r16", "1.0"));
        assertTrue(first.success());
        assertEquals(List.of("language"), unregisteredCommands, "the proxy released its command");
        assertEquals(Set.of("lifesteal"), companions.servers());

        call(backend, announce("r17", "1.0"));
        assertEquals(List.of("language"), unregisteredCommands, "unregistering again would be pointless");
    }

    @Test
    @DisplayName("announce works without a valid uuid, since no player is involved")
    void announceIgnoresUuid() throws Exception {
        Reply reply = call(backend, announce("r18", ""));

        assertTrue(reply.success());
        assertEquals(LanguageChannel.ERROR_NONE, reply.error());
    }

    @Test
    @DisplayName("get_language_menu carries the strings and every language, named in itself")
    void menuCarriesEverythingTheSelectorNeeds() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "cs_CZ");

        Reply reply = call(backend, request(LanguageChannel.OP_MENU, "r19", id.toString(), null));

        assertTrue(reply.success());
        assertEquals("cs_CZ", reply.language(), "the player's current language");

        Menu menu = reply.menu();
        assertEquals("<dark_gray>Vyber si jazyk", menu.title(), "menu chrome is in the player's language");
        assertEquals("<gray>Klikni pro použití tohoto jazyka.", menu.selectHint());
        assertEquals("<green>Tento jazyk právě používáš.", menu.currentHint());
        assertEquals(List.of("cs_CZ", "en_US"), List.copyOf(menu.entries().keySet()));
        assertEquals("Čeština", menu.entries().get("cs_CZ"), "each language names itself");
        assertEquals("English (US)", menu.entries().get("en_US"));
    }

    @Test
    @DisplayName("the menu opens for a player who has never chosen, showing the default")
    void menuForUnknownPlayer() throws Exception {
        Reply reply = call(backend, request(LanguageChannel.OP_MENU, "r20", UUID.randomUUID().toString(), null));

        assertTrue(reply.success(), "an unknown player has simply not chosen yet");
        assertEquals("en_US", reply.language());
        assertEquals("<dark_gray>Select your language", reply.menu().title());
    }

    @Test
    @DisplayName("a successful set tells the online player, in the new language")
    void setConfirmsToThePlayer() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");
        online.add(id);

        call(backend, request(LanguageChannel.OP_SET, "r21", id.toString(), "cs_CZ"));

        assertEquals(1, sentToPlayer.size(), "the proxy owns the translated confirmation");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(sentToPlayer.get(0))
                        .contains("Tvůj jazyk je nyní cs_CZ"),
                () -> "unexpected message: " + sentToPlayer);
    }

    @Test
    @DisplayName("setting the language of an offline player messages nobody")
    void setForOfflinePlayerIsSilent() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        call(backend, request(LanguageChannel.OP_SET, "r22", id.toString(), "cs_CZ"));

        assertTrue(sentToPlayer.isEmpty());
    }

    /**
     * The companion plugin is a separate artifact that repeats these protocol
     * constants, so its encoder's exact output is pinned here. Its
     * {@code ProtocolCodecTest.GOLDEN_ANNOUNCE} asserts it writes these very
     * bytes; this asserts the proxy understands them.
     */
    @Test
    @DisplayName("the companion's exact announce bytes are understood")
    void understandsTheCompanionsGoldenBytes() throws Exception {
        byte[] fromCompanion = HexFormat.of().parseHex(
                "0008616e6e6f756e636500027231002430303030303030302d303030302d303030302d"
                        + "303030302d3030303030303030303030300003302e31");

        Reply reply = call(backend, fromCompanion);

        assertTrue(reply.success(), "the proxy must accept the companion's encoding verbatim");
        assertEquals(LanguageChannel.OP_ANNOUNCE, reply.operation());
        assertEquals("r1", reply.requestId());
        assertEquals(Set.of("lifesteal"), companions.servers());
        assertEquals(List.of("language"), unregisteredCommands);
    }
}
