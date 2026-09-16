package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.ChatMessageRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives real {@link PluginMessageEvent}s through the listener against an H2
 * database, with a stand-in for the backend connection.
 */
class ChatChannelListenerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    private DatabaseManager database;
    private ChatMessageRepository chatMessages;
    private ChatChannelListener listener;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        var logger = LoggerFactory.getLogger(ChatChannelListenerTest.class);
        ConfigManager config = new ConfigManager(logger, directory);
        config.load();

        database = new DatabaseManager(logger, directory);
        database.start(config.config().database());
        chatMessages = new ChatMessageRepository(database);
        chatMessages.createTable();

        listener = new ChatChannelListener(logger, database, chatMessages, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    // --- stubs -------------------------------------------------------------

    private static ServerConnection stubBackend() {
        return (ServerConnection) Proxy.newProxyInstance(ChatChannelListenerTest.class.getClassLoader(),
                new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> new ServerInfo("lifesteal", new InetSocketAddress("127.0.0.1", 25566));
                    case "toString" -> "StubBackend";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> null;
                });
    }

    private static Player stubPlayer() {
        return (Player) Proxy.newProxyInstance(ChatChannelListenerTest.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "StubPlayer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 3;
                    default -> null;
                });
    }

    // --- helpers -----------------------------------------------------------

    private static byte[] json(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] report(UUID player, String name, String content) {
        return json("{\"action\":\"player_msg\",\"player\":\"" + player
                + "\",\"player_name\":\"" + name + "\",\"content\":\"" + content + "\"}");
    }

    /** Delivers a message, waits for any database work, and says whether it was forwarded. */
    private boolean deliver(ChannelMessageSource source, MinecraftChannelIdentifier channel, byte[] data)
            throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(source, stubBackend(), channel, data);

        EventTask task = listener.onPluginMessage(event);
        if (task != null) {
            var done = new CountDownLatch(1);
            task.execute((Continuation) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Continuation.class}, (p, m, a) -> {
                        done.countDown();
                        return null;
                    }));
            assertTrue(done.await(30, TimeUnit.SECONDS), "the async task should finish");
        }
        return event.getResult().isAllowed();
    }

    private boolean deliver(byte[] data) throws Exception {
        return deliver(stubBackend(), ChatChannel.IDENTIFIER, data);
    }

    // --- tests -------------------------------------------------------------

    @Test
    @DisplayName("a player_msg report is logged with the backend's name and the time of receipt")
    void logsReport() throws Exception {
        boolean forwarded = deliver(report(PLAYER, "Notch", "hello world"));

        assertFalse(forwarded, "the report must never reach the client");
        assertEquals(List.of(new ChatMessage(PLAYER, "Notch", "lifesteal", "hello world", NOW)),
                chatMessages.findByPlayer(PLAYER));
    }

    @Test
    @DisplayName("field order does not matter and unknown fields are ignored")
    void toleratesExtraFields() throws Exception {
        deliver(json("""
                {"content": "řeřicha", "extra": 1, "player_name": "Notch",
                 "player": "%s", "action": "player_msg"}""".formatted(PLAYER)));

        assertEquals("řeřicha", chatMessages.findByPlayer(PLAYER).get(0).content());
    }

    @Test
    @DisplayName("an empty message is still a message")
    void emptyContentLogged() throws Exception {
        deliver(report(PLAYER, "Notch", ""));

        assertEquals(1, chatMessages.findByPlayer(PLAYER).size());
    }

    @Test
    @DisplayName("malformed reports are dropped without logging anything")
    void malformedReportsDropped() throws Exception {
        String id = PLAYER.toString();
        List<byte[]> bad = List.of(
                new byte[]{1, 2, 3},
                json("not json at all {"),
                json("[\"player_msg\"]"),
                json("{\"action\":\"player_left\",\"player\":\"" + id + "\",\"player_name\":\"a\",\"content\":\"x\"}"),
                json("{\"player\":\"" + id + "\",\"player_name\":\"a\",\"content\":\"x\"}"),
                json("{\"action\":\"player_msg\",\"player\":\"nope\",\"player_name\":\"a\",\"content\":\"x\"}"),
                json("{\"action\":\"player_msg\",\"player\":\"" + id + "\",\"content\":\"x\"}"),
                json("{\"action\":\"player_msg\",\"player\":\"" + id + "\",\"player_name\":\"a\",\"content\":5}"),
                json("{\"action\":\"player_msg\",\"player\":\"" + id + "\",\"player_name\":\"a\",\"content\":null}"),
                report(PLAYER, "n".repeat(ChatMessage.PLAYER_NAME_MAX_LENGTH + 1), "x"));

        for (byte[] data : bad) {
            assertFalse(deliver(data), "even a bad report is kept from the client");
        }
        assertTrue(chatMessages.findByPlayer(PLAYER).isEmpty());
    }

    @Test
    @DisplayName("a report from a client is ignored entirely")
    void clientReportsIgnored() throws Exception {
        boolean forwarded = deliver(stubPlayer(), ChatChannel.IDENTIFIER, report(PLAYER, "Notch", "forged"));

        assertFalse(forwarded);
        assertTrue(chatMessages.findByPlayer(PLAYER).isEmpty(), "a client cannot write to the chat log");
    }

    @Test
    @DisplayName("other channels pass through untouched")
    void otherChannelsForwarded() throws Exception {
        assertTrue(deliver(stubBackend(), MinecraftChannelIdentifier.create("bungeecord", "main"),
                report(PLAYER, "Notch", "hi")));
        assertTrue(chatMessages.findByPlayer(PLAYER).isEmpty());
    }

    @Test
    @DisplayName("with the database down reports are dropped quietly and still not forwarded")
    void databaseDown() throws Exception {
        database.close();

        PluginMessageEvent event = new PluginMessageEvent(stubBackend(), stubBackend(),
                ChatChannel.IDENTIFIER, report(PLAYER, "Notch", "hi"));

        assertNull(listener.onPluginMessage(event), "no database work is scheduled");
        assertFalse(event.getResult().isAllowed());
    }

    /**
     * The companion plugin is a separate artifact that repeats these protocol
     * constants, so its encoder's exact output is pinned here. Its
     * {@code ChatCodecTest.GOLDEN_PLAYER_MSG} asserts it writes these very
     * bytes; this asserts the proxy understands them.
     */
    @Test
    @DisplayName("the companion's exact player_msg bytes are understood")
    void understandsTheCompanionsGoldenBytes() throws Exception {
        byte[] fromCompanion = HexFormat.of().parseHex(
                "7b22616374696f6e223a22706c617965725f6d7367222c22706c61796572223a2231653166326233632d346435652d36"
                        + "6637302d383139322d613362346335643665376638222c22706c617965725f6e616d65223a224e6f746368222c"
                        + "22636f6e74656e74223a226869205c2274686572655c22203c3e227d");

        deliver(fromCompanion);

        assertEquals(List.of(new ChatMessage(PLAYER, "Notch", "lifesteal", "hi \"there\" <>", NOW)),
                chatMessages.findByPlayer(PLAYER));
    }
}
