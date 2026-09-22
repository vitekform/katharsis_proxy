package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.ChatMessageRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.entity.FlaggedChatMessage;
import me.ganamaga.katharsis_proxy.moderation.ChatModerator;
import me.ganamaga.katharsis_proxy.moderation.ModerationSource;
import me.ganamaga.katharsis_proxy.moderation.ModerationVerdict;
import me.ganamaga.katharsis_proxy.moderation.OpenAiModeration;
import me.ganamaga.katharsis_proxy.moderation.RateLimitBudget;
import me.ganamaga.katharsis_proxy.moderation.RuleFilter;
import me.ganamaga.katharsis_proxy.util.DebugLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of the feature, end to end: a message the filters object to
 * reaches neither Discord nor the ordinary chat log, and is accounted for
 * everywhere it should be.
 */
class ChatChannelListenerModerationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatChannelListenerModerationTest.class);
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    private DatabaseManager database;
    private ChatMessageRepository chatMessages;

    /** Server id and message of every relayed report, in order. */
    private final List<Map.Entry<String, ChatMessage>> relayed = new CopyOnWriteArrayList<>();

    /** Every message reported to the staff channel, with its verdict. */
    private final List<Map.Entry<ChatMessage, ModerationVerdict>> alerted = new CopyOnWriteArrayList<>();

    /** Every player told their message did not go through. */
    private final List<UUID> warned = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        ConfigManager config = new ConfigManager(LOGGER, directory);
        config.load();

        database = new DatabaseManager(LOGGER, directory);
        database.start(config.config().database());
        chatMessages = new ChatMessageRepository(database);
        chatMessages.createTable();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private ChatChannelListener listener(ChatModerator moderator) {
        return new ChatChannelListener(LOGGER, database, chatMessages,
                (serverId, message) -> relayed.add(Map.entry(serverId, message)),
                new CompanionRegistry(LOGGER), moderator,
                (message, verdict) -> alerted.add(Map.entry(message, verdict)),
                (playerUuid, verdict) -> warned.add(playerUuid),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Rules only, so nothing leaves the machine. */
    private static ChatModerator withRules(String... terms) {
        return new ChatModerator(LOGGER, RuleFilter.compile(LOGGER, List.of(terms), List.of(), List.of()), null);
    }

    /** A model layer pointed at a port nothing is listening on, so every call fails. */
    private static ChatModerator withUnreachableModel() {
        OpenAiModeration openAi = new OpenAiModeration(LOGGER, DebugLog.DISABLED, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:1/v1/moderations"), "sk-test", "omni-moderation-latest",
                Duration.ofSeconds(2L), new RateLimitBudget(Clock.systemUTC(), 0, 0, 0), List.of(), 0.0d);
        return new ChatModerator(LOGGER, RuleFilter.empty(), openAi);
    }

    private static ServerConnection stubBackend() {
        return (ServerConnection) Proxy.newProxyInstance(ChatChannelListenerModerationTest.class.getClassLoader(),
                new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> new ServerInfo("lifesteal", new InetSocketAddress("127.0.0.1", 25566));
                    case "toString" -> "StubBackend";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> null;
                });
    }

    private static byte[] report(UUID player, String name, String content) {
        return ("{\"action\":\"player_msg\",\"player\":\"" + player + "\",\"player_name\":\"" + name
                + "\",\"content\":\"" + content + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** Delivers a report and waits for the moderation and any database work. */
    private void deliver(ChatChannelListener listener, byte[] data) throws Exception {
        ServerConnection backend = stubBackend();
        EventTask task = listener.onPluginMessage(
                new PluginMessageEvent(backend, backend, ChatChannel.IDENTIFIER, data));
        if (task == null) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        task.execute((Continuation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Continuation.class}, (p, m, a) -> {
                    done.countDown();
                    return null;
                }));
        assertTrue(done.await(30L, TimeUnit.SECONDS), "the async task should finish");
    }

    @Test
    @DisplayName("a clean message is relayed and logged as it always was")
    void passesCleanMessages() throws Exception {
        deliver(listener(withRules("racist")), report(PLAYER, "Notch", "what a lovely evening"));

        ChatMessage expected = new ChatMessage(PLAYER, "Notch", "lifesteal", "what a lovely evening", NOW);
        assertEquals(List.of(Map.entry("lifesteal", expected)), relayed);
        assertEquals(List.of(expected), chatMessages.findByPlayer(PLAYER));
        assertTrue(chatMessages.findFlaggedByPlayer(PLAYER).isEmpty());
        assertTrue(alerted.isEmpty());
        assertTrue(warned.isEmpty());
    }

    @Test
    @DisplayName("a flagged message reaches neither Discord nor the queue behind it")
    void holdsFlaggedMessagesBack() throws Exception {
        deliver(listener(withRules("racist")), report(PLAYER, "Notch", "you are r a c i s t"));

        assertTrue(relayed.isEmpty(), "nothing flagged may reach Discord");
    }

    @Test
    @DisplayName("a flagged message is stored with what caught it, and its author is told")
    void recordsFlaggedMessages() throws Exception {
        deliver(listener(withRules("racist")), report(PLAYER, "Notch", "you are r4c1st"));

        List<FlaggedChatMessage> flagged = chatMessages.findFlaggedByPlayer(PLAYER);
        assertEquals(1, flagged.size());
        assertEquals("you are r4c1st", flagged.get(0).message().content());
        assertEquals(ModerationSource.RULES.id(), flagged.get(0).source());
        assertEquals("word: racist", flagged.get(0).reason());

        assertEquals(List.of(PLAYER), warned);
        assertEquals(1, alerted.size());
        assertEquals(ModerationSource.RULES, alerted.get(0).getValue().source());
    }

    @Test
    @DisplayName("the flagged message still shows up in the player's history")
    void keepsFlaggedMessagesInTheHistory() throws Exception {
        ChatChannelListener listener = listener(withRules("racist"));
        deliver(listener, report(PLAYER, "Notch", "hello"));
        deliver(listener, report(PLAYER, "Notch", "you are racist"));

        assertEquals(List.of("hello", "you are racist"),
                chatMessages.findByPlayer(PLAYER).stream().map(ChatMessage::content).toList());
    }

    @Test
    @DisplayName("a message nothing could check is held back, and recorded as unavailable rather than as a violation")
    void holdsBackWhatCannotBeChecked() throws Exception {
        deliver(listener(withUnreachableModel()), report(PLAYER, "Notch", "an ordinary message"));

        assertTrue(relayed.isEmpty(), "an unchecked message is exactly what this exists to stop");
        List<FlaggedChatMessage> flagged = chatMessages.findFlaggedByPlayer(PLAYER);
        assertEquals(1, flagged.size());
        assertEquals(ModerationSource.UNAVAILABLE.id(), flagged.get(0).source());
        assertEquals(List.of(PLAYER), warned);
        // The sink is still told; it decides for itself not to wake the staff.
        assertEquals(ModerationSource.UNAVAILABLE, alerted.get(0).getValue().source());
    }

    @Test
    @DisplayName("a held-back message is kept from the client like any other report")
    void neverForwardsToTheClient() throws Exception {
        ServerConnection backend = stubBackend();
        PluginMessageEvent event = new PluginMessageEvent(backend, backend, ChatChannel.IDENTIFIER,
                report(PLAYER, "Notch", "you are racist"));

        EventTask task = listener(withRules("racist")).onPluginMessage(event);
        CountDownLatch done = new CountDownLatch(1);
        task.execute((Continuation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Continuation.class}, (p, m, a) -> {
                    done.countDown();
                    return null;
                }));
        assertTrue(done.await(30L, TimeUnit.SECONDS));

        assertEquals(PluginMessageEvent.ForwardResult.handled(), event.getResult());
    }
}
