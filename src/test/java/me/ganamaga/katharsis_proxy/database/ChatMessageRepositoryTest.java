package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.entity.FlaggedChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-09-16T12:00:00.123456Z");

    private DatabaseManager database;
    private ChatMessageRepository chatMessages;

    @BeforeEach
    void openDatabase(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        chatMessages = new ChatMessageRepository(database);
        chatMessages.createTable();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    @DisplayName("createTable can run again on an existing schema")
    void createTableIsIdempotent() throws SQLException {
        chatMessages.createTable();
        chatMessages.createTable();
    }

    @Test
    @DisplayName("a message round-trips with every field intact")
    void roundTrip() throws SQLException {
        UUID id = UUID.randomUUID();
        ChatMessage message = new ChatMessage(id, "Notch", "lifesteal", "ahoj, jak se máš? 👋", T0);

        chatMessages.insert(message);

        assertEquals(List.of(message), chatMessages.findByPlayer(id));
    }

    @Test
    @DisplayName("identical messages are all kept, oldest first, and only for their author")
    void appendsInOrder() throws SQLException {
        UUID author = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        chatMessages.insert(new ChatMessage(author, "a", "lobby", "gg", T0.plusSeconds(5)));
        chatMessages.insert(new ChatMessage(author, "a", "lobby", "gg", T0));
        chatMessages.insert(new ChatMessage(author, "a", "lobby", "gg", T0.plusSeconds(5)));
        chatMessages.insert(new ChatMessage(someoneElse, "b", "lobby", "hi", T0));

        List<ChatMessage> logged = chatMessages.findByPlayer(author);

        assertEquals(3, logged.size(), "a log never deduplicates");
        assertEquals(T0, logged.get(0).sentAt());
        assertTrue(chatMessages.findByPlayer(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("a long message is stored whole")
    void longContent() throws SQLException {
        UUID id = UUID.randomUUID();
        String content = "x".repeat(10_000);

        chatMessages.insert(new ChatMessage(id, "a", "lobby", content, T0));

        assertEquals(content, chatMessages.findByPlayer(id).get(0).content());
    }

    @Test
    @DisplayName("a name too wide for its column is refused before it reaches SQL")
    void overlongNameRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(UUID.randomUUID(),
                "n".repeat(ChatMessage.PLAYER_NAME_MAX_LENGTH + 1), "lobby", "hi", T0));
    }

    @Test
    @DisplayName("a held-back message is stored with what caught it, and read back on its own")
    void storesFlaggedMessages() throws SQLException {
        UUID id = UUID.randomUUID();
        ChatMessage clean = new ChatMessage(id, "a", "lobby", "hello", T0);
        ChatMessage held = new ChatMessage(id, "a", "lobby", "you are racist", T0.plusSeconds(5));

        chatMessages.insert(clean);
        chatMessages.insert(new FlaggedChatMessage(held, "rules", "word: racist"));

        // Both are in the history, in order.
        assertEquals(List.of(clean, held), chatMessages.findByPlayer(id));

        List<FlaggedChatMessage> flagged = chatMessages.findFlaggedByPlayer(id);
        assertEquals(1, flagged.size());
        assertEquals(held, flagged.get(0).message());
        assertEquals("rules", flagged.get(0).source());
        assertEquals("word: racist", flagged.get(0).reason());
    }

    @Test
    @DisplayName("a clean message is not flagged")
    void cleanMessagesAreNotFlagged() throws SQLException {
        UUID id = UUID.randomUUID();
        chatMessages.insert(new ChatMessage(id, "a", "lobby", "hello", T0));

        assertTrue(chatMessages.findFlaggedByPlayer(id).isEmpty());
        assertFalse(chatMessages.findByPlayer(id).isEmpty());
    }

    @Test
    @DisplayName("an overlong reason is cut to fit rather than costing the log the message")
    void overlongReasonCut() {
        String reason = "r".repeat(FlaggedChatMessage.REASON_MAX_LENGTH + 50);
        FlaggedChatMessage flagged = new FlaggedChatMessage(
                new ChatMessage(UUID.randomUUID(), "a", "lobby", "hi", T0), "openai", reason);

        assertEquals(FlaggedChatMessage.REASON_MAX_LENGTH, flagged.reason().length());
    }

    @Test
    @DisplayName("a log written before moderation existed gains the flag columns on the next start")
    void migratesAnOlderLog() throws Exception {
        // The table exactly as it was shipped before moderation.
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE katharsis_chat_messages");
            statement.execute("""
                    CREATE TABLE katharsis_chat_messages (
                        id          BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        player_uuid UUID                     NOT NULL,
                        player_name VARCHAR(64)              NOT NULL,
                        server      VARCHAR(255)             NOT NULL,
                        content     TEXT                     NOT NULL,
                        sent_at     TIMESTAMP WITH TIME ZONE NOT NULL
                    )""");
            statement.execute("INSERT INTO katharsis_chat_messages (player_uuid, player_name, server, content,"
                    + " sent_at) VALUES ('11111111-1111-1111-1111-111111111111', 'old', 'lobby', 'hi',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-16 12:00:00+00')");
        }

        chatMessages.createTable();

        UUID old = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertEquals(1, chatMessages.findByPlayer(old).size(), "the rows that were there are kept");
        assertTrue(chatMessages.findFlaggedByPlayer(old).isEmpty(), "and none of them counts as flagged");

        // And the new columns work from here on.
        UUID id = UUID.randomUUID();
        chatMessages.insert(new FlaggedChatMessage(
                new ChatMessage(id, "a", "lobby", "bad", T0), "openai", "hate"));
        assertEquals("hate", chatMessages.findFlaggedByPlayer(id).get(0).reason());
    }
}
