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
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
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
    private ServerConnection backend;

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
        listener = new LanguageChannelListener(stubProxy(), logger, registry, database, players, cache);
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
                    case "toString" -> "StubProxy";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 2;
                    default -> null;
                });
    }

    private Player stubPlayer() {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "StubPlayer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 3;
                    default -> null;
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

    private record Reply(String operation, String requestId, String uuid,
                         boolean success, String language, String error) {
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
        return new Delivery(new Reply(in.readUTF(), in.readUTF(), in.readUTF(),
                in.readBoolean(), in.readUTF(), in.readUTF()), forwarded);
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
}
