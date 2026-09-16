package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.ChatMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Appends to and reads the chat log.
 */
public final class ChatMessageRepository {

    /**
     * No foreign key to {@code katharsis_players}: a report may arrive for a
     * player whose row could not be created, and the log must not lose it.
     */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS katharsis_chat_messages (
                id          BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                player_uuid UUID                     NOT NULL,
                player_name VARCHAR(64)              NOT NULL,
                server      VARCHAR(255)             NOT NULL,
                content     TEXT                     NOT NULL,
                sent_at     TIMESTAMP WITH TIME ZONE NOT NULL
            )""";

    private static final String CREATE_PLAYER_INDEX = """
            CREATE INDEX IF NOT EXISTS katharsis_chat_messages_player
            ON katharsis_chat_messages (player_uuid, sent_at)""";

    private static final String INSERT = """
            INSERT INTO katharsis_chat_messages (player_uuid, player_name, server, content, sent_at)
            VALUES (?, ?, ?, ?, ?)""";

    private static final String SELECT_BY_PLAYER = """
            SELECT player_uuid, player_name, server, content, sent_at
            FROM katharsis_chat_messages
            WHERE player_uuid = ?
            ORDER BY sent_at, id""";

    private final DatabaseManager database;

    public ChatMessageRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing table and its index if they do not exist yet. */
    public void createTable() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_PLAYER_INDEX);
        }
    }

    /** Appends a message to the log. */
    public void insert(ChatMessage message) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, message.playerUuid());
            statement.setString(2, message.playerName());
            statement.setString(3, message.server());
            statement.setString(4, message.content());
            statement.setObject(5, OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    /** Every logged message by {@code playerUuid}, oldest first. */
    public List<ChatMessage> findByPlayer(UUID playerUuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_PLAYER)) {
            statement.setObject(1, playerUuid);
            try (ResultSet results = statement.executeQuery()) {
                List<ChatMessage> messages = new ArrayList<>();
                while (results.next()) {
                    messages.add(new ChatMessage(
                            results.getObject("player_uuid", UUID.class),
                            results.getString("player_name"),
                            results.getString("server"),
                            results.getString("content"),
                            results.getObject("sent_at", OffsetDateTime.class).toInstant()));
                }
                return messages;
            }
        }
    }
}
