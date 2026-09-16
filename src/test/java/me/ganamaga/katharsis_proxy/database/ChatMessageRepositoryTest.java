package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
