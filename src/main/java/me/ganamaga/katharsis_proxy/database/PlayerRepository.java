package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes {@link Player} rows.
 */
public final class PlayerRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS katharsis_players (
                uuid         UUID        NOT NULL PRIMARY KEY,
                language     VARCHAR(5)  NOT NULL DEFAULT 'en_US',
                discord_chat BOOLEAN     NOT NULL DEFAULT FALSE
            )""";

    /** Brings a table created before Discord chat could be hidden up to date. */
    private static final String ADD_DISCORD_CHAT = """
            ALTER TABLE katharsis_players
            ADD COLUMN IF NOT EXISTS discord_chat BOOLEAN NOT NULL DEFAULT FALSE""";

    /**
     * Insert-if-absent written portably: PostgreSQL's {@code ON CONFLICT} is not
     * understood by H2, even in PostgreSQL compatibility mode. The casts spell
     * out the parameter types so neither driver has to infer them.
     */
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO katharsis_players (uuid, language)
            SELECT CAST(? AS UUID), CAST(? AS VARCHAR(5))
            WHERE NOT EXISTS (SELECT 1 FROM katharsis_players WHERE uuid = CAST(? AS UUID))""";

    /** SQLState for a unique/primary key violation, shared by PostgreSQL and H2. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String SELECT_BY_UUID = """
            SELECT uuid, language, discord_chat
            FROM katharsis_players
            WHERE uuid = ?""";

    private static final String UPDATE_LANGUAGE = """
            UPDATE katharsis_players
            SET language = ?
            WHERE uuid = ?""";

    private static final String UPDATE_DISCORD_CHAT = """
            UPDATE katharsis_players
            SET discord_chat = ?
            WHERE uuid = ?""";

    private final DatabaseManager database;

    public PlayerRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing table if it does not exist yet, and adds any column it lacks. */
    public void createTable() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(ADD_DISCORD_CHAT);
        }
    }

    /**
     * Inserts a row for {@code uuid} unless one already exists.
     *
     * @return {@code true} if a new row was created, {@code false} if the
     *         player was already known
     * @throws IllegalArgumentException if {@code language} does not fit the column
     */
    public boolean createIfAbsent(UUID uuid, String language) throws SQLException {
        // Constructing the entity applies the column's length rule.
        Player player = new Player(uuid, language);
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_IF_ABSENT)) {
            statement.setObject(1, player.uuid());
            statement.setString(2, player.language());
            statement.setObject(3, player.uuid());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                // Someone else inserted the same player between the check and
                // the insert; the row exists either way.
                return false;
            }
            throw e;
        }
    }

    /** Looks up a player by uuid. */
    public Optional<Player> find(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_UUID)) {
            statement.setObject(1, uuid);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Player(
                        results.getObject("uuid", UUID.class),
                        results.getString("language"),
                        results.getBoolean("discord_chat")));
            }
        }
    }

    /**
     * Changes a player's language.
     *
     * @return {@code true} if a row was updated
     * @throws IllegalArgumentException if {@code language} does not fit the column
     */
    public boolean updateLanguage(UUID uuid, String language) throws SQLException {
        new Player(uuid, language); // applies the column's length rule
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_LANGUAGE)) {
            statement.setString(1, language);
            statement.setObject(2, uuid);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Shows or hides the Discord chat channels in game for a player.
     *
     * @return {@code true} if a row was updated
     */
    public boolean updateDiscordChat(UUID uuid, boolean shown) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_DISCORD_CHAT)) {
            statement.setBoolean(1, shown);
            statement.setObject(2, uuid);
            return statement.executeUpdate() > 0;
        }
    }
}
