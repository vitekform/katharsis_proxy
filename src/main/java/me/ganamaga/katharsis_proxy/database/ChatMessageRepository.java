package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.entity.FlaggedChatMessage;

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
 * Appends to and reads the chat log, flagged messages included.
 *
 * <p>Messages the moderation filters held back are kept here too, marked with
 * what stopped them, rather than in a table of their own: a moderator reading
 * one player's history wants what was said and what was stopped in one place
 * and in order.</p>
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
                sent_at     TIMESTAMP WITH TIME ZONE NOT NULL,
                flagged     BOOLEAN                  NOT NULL DEFAULT FALSE,
                flag_source VARCHAR(16),
                flag_reason VARCHAR(255)
            )""";

    /**
     * Brings a log written before moderation existed up to date. Both H2 and
     * PostgreSQL take {@code IF NOT EXISTS} here, so this is safe to run on
     * every start and costs nothing once the columns are there.
     */
    private static final String[] ADD_FLAG_COLUMNS = {
            "ALTER TABLE katharsis_chat_messages ADD COLUMN IF NOT EXISTS flagged BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE katharsis_chat_messages ADD COLUMN IF NOT EXISTS flag_source VARCHAR(16)",
            "ALTER TABLE katharsis_chat_messages ADD COLUMN IF NOT EXISTS flag_reason VARCHAR(255)"
    };

    private static final String CREATE_PLAYER_INDEX = """
            CREATE INDEX IF NOT EXISTS katharsis_chat_messages_player
            ON katharsis_chat_messages (player_uuid, sent_at)""";

    private static final String CREATE_FLAGGED_INDEX = """
            CREATE INDEX IF NOT EXISTS katharsis_chat_messages_flagged
            ON katharsis_chat_messages (flagged, sent_at)""";

    private static final String INSERT = """
            INSERT INTO katharsis_chat_messages
                (player_uuid, player_name, server, content, sent_at, flagged, flag_source, flag_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_BY_PLAYER = """
            SELECT player_uuid, player_name, server, content, sent_at
            FROM katharsis_chat_messages
            WHERE player_uuid = ?
            ORDER BY sent_at, id""";

    private static final String SELECT_FLAGGED_BY_PLAYER = """
            SELECT player_uuid, player_name, server, content, sent_at, flag_source, flag_reason
            FROM katharsis_chat_messages
            WHERE player_uuid = ? AND flagged = TRUE
            ORDER BY sent_at, id""";

    private final DatabaseManager database;

    public ChatMessageRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing table and its indexes if they do not exist yet. */
    public void createTable() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            for (String migration : ADD_FLAG_COLUMNS) {
                statement.execute(migration);
            }
            statement.execute(CREATE_PLAYER_INDEX);
            statement.execute(CREATE_FLAGGED_INDEX);
        }
    }

    /** Appends a message the filters had nothing against. */
    public void insert(ChatMessage message) throws SQLException {
        insert(message, null, null);
    }

    /** Appends a message the filters held back, with what stopped it. */
    public void insert(FlaggedChatMessage flagged) throws SQLException {
        insert(flagged.message(), flagged.source(), flagged.reason());
    }

    private void insert(ChatMessage message, String flagSource, String flagReason) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, message.playerUuid());
            statement.setString(2, message.playerName());
            statement.setString(3, message.server());
            statement.setString(4, message.content());
            statement.setObject(5, OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC));
            statement.setBoolean(6, flagSource != null);
            statement.setString(7, flagSource);
            statement.setString(8, flagReason);
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

    /** Every message by {@code playerUuid} the filters held back, oldest first. */
    public List<FlaggedChatMessage> findFlaggedByPlayer(UUID playerUuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_FLAGGED_BY_PLAYER)) {
            statement.setObject(1, playerUuid);
            try (ResultSet results = statement.executeQuery()) {
                List<FlaggedChatMessage> messages = new ArrayList<>();
                while (results.next()) {
                    messages.add(new FlaggedChatMessage(
                            new ChatMessage(
                                    results.getObject("player_uuid", UUID.class),
                                    results.getString("player_name"),
                                    results.getString("server"),
                                    results.getString("content"),
                                    results.getObject("sent_at", OffsetDateTime.class).toInstant()),
                            results.getString("flag_source"),
                            results.getString("flag_reason")));
                }
                return messages;
            }
        }
    }
}
