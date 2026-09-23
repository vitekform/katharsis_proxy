package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.AuthAccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the names players have claimed and the password hashes they log in
 * with.
 */
public final class AuthAccountRepository {

    /**
     * Keyed on the lower-cased name, so two spellings of one name cannot both be
     * claimed. No foreign key to {@code katharsis_players}: that row is keyed on
     * a uuid, and whoever joins first under a name gets one whether or not they
     * ever log in.
     */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS katharsis_auth_accounts (
                username_key  VARCHAR(32)              NOT NULL PRIMARY KEY,
                username      VARCHAR(32)              NOT NULL,
                player_uuid   UUID                     NOT NULL,
                password_hash VARCHAR(255),
                premium       BOOLEAN                  NOT NULL,
                registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
                last_login_at TIMESTAMP WITH TIME ZONE
            )""";

    private static final String COLUMNS =
            "username, player_uuid, password_hash, premium, registered_at, last_login_at";

    private static final String SELECT =
            "SELECT " + COLUMNS + " FROM katharsis_auth_accounts WHERE username_key = ?";

    private static final String INSERT =
            "INSERT INTO katharsis_auth_accounts (username_key, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_LOGIN =
            "UPDATE katharsis_auth_accounts SET last_login_at = ? WHERE username_key = ?";

    private static final String UPDATE_PASSWORD =
            "UPDATE katharsis_auth_accounts SET password_hash = ? WHERE username_key = ? AND premium = FALSE";

    /**
     * Mojang has the final word on who holds a premium name, so whoever it last
     * logged in under that name becomes the owner of record.
     */
    private static final String UPDATE_PREMIUM = """
            UPDATE katharsis_auth_accounts
            SET username = ?, player_uuid = ?, last_login_at = ?
            WHERE username_key = ? AND premium = TRUE""";

    /** SQLState for a unique/primary key violation, shared by PostgreSQL and H2. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DatabaseManager database;

    public AuthAccountRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing table if it does not exist yet. */
    public void createTable() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
        }
    }

    /** The account holding {@code username} in any case, if one does. */
    public Optional<AuthAccount> find(String username) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, AuthAccount.key(username));
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(read(results));
            }
        }
    }

    /**
     * Claims the account's name.
     *
     * @return {@code false} if the name, in any case, is already taken; nothing
     *         changed then
     */
    public boolean insert(AuthAccount account) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, account.key());
            statement.setString(2, account.username());
            statement.setObject(3, account.playerUuid());
            statement.setString(4, account.passwordHash());
            statement.setBoolean(5, account.premium());
            statement.setObject(6, utc(account.registeredAt()));
            if (account.lastLoginAt() == null) {
                statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setObject(7, utc(account.lastLoginAt()));
            }
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    /** Stamps a successful login. */
    public void recordLogin(String username, Instant now) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_LOGIN)) {
            statement.setObject(1, utc(now));
            statement.setString(2, AuthAccount.key(username));
            statement.executeUpdate();
        }
    }

    /**
     * Replaces the password of a password account.
     *
     * @return {@code false} if there is no such account
     */
    public boolean updatePassword(String username, String passwordHash) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_PASSWORD)) {
            statement.setString(1, passwordHash);
            statement.setString(2, AuthAccount.key(username));
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Records that Mojang logged {@code username} in as {@code playerUuid},
     * claiming the name for them if it is free.
     *
     * @return {@code false} if the name is held by a password account, which is
     *         left alone
     */
    public boolean savePremium(String username, UUID playerUuid, Instant now) throws SQLException {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_PREMIUM)) {
                update.setString(1, username);
                update.setObject(2, playerUuid);
                update.setObject(3, utc(now));
                update.setString(4, AuthAccount.key(username));
                if (update.executeUpdate() > 0) {
                    return true;
                }
            }
        }
        return insert(AuthAccount.premium(username, playerUuid, now));
    }

    private static AuthAccount read(ResultSet results) throws SQLException {
        OffsetDateTime lastLogin = results.getObject("last_login_at", OffsetDateTime.class);
        return new AuthAccount(
                results.getString("username"),
                results.getObject("player_uuid", UUID.class),
                results.getString("password_hash"),
                results.getBoolean("premium"),
                results.getObject("registered_at", OffsetDateTime.class).toInstant(),
                lastLogin == null ? null : lastLogin.toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
