package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.entity.LinkCode;
import me.ganamaga.katharsis_proxy.link.RedeemResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores account links and the one-time codes that create them.
 */
public final class AccountLinkRepository {

    /**
     * Both columns are unique, so neither account can be linked twice. No
     * foreign key to {@code katharsis_players}, for the same reason as the chat
     * log: a link must not be lost because that row could not be created.
     */
    private static final String CREATE_LINKS_TABLE = """
            CREATE TABLE IF NOT EXISTS katharsis_account_links (
                player_uuid      UUID                     NOT NULL PRIMARY KEY,
                player_name      VARCHAR(64)              NOT NULL,
                discord_user_id  BIGINT                   NOT NULL UNIQUE,
                discord_username VARCHAR(64)              NOT NULL,
                linked_at        TIMESTAMP WITH TIME ZONE NOT NULL
            )""";

    /** One outstanding code per Discord user. */
    private static final String CREATE_CODES_TABLE = """
            CREATE TABLE IF NOT EXISTS katharsis_link_codes (
                code             VARCHAR(16)              NOT NULL PRIMARY KEY,
                discord_user_id  BIGINT                   NOT NULL UNIQUE,
                discord_username VARCHAR(64)              NOT NULL,
                expires_at       TIMESTAMP WITH TIME ZONE NOT NULL
            )""";

    private static final String LINK_COLUMNS = "player_uuid, player_name, discord_user_id, discord_username, linked_at";

    private static final String SELECT_LINK_BY_PLAYER =
            "SELECT " + LINK_COLUMNS + " FROM katharsis_account_links WHERE player_uuid = ?";

    private static final String SELECT_LINK_BY_DISCORD_USER =
            "SELECT " + LINK_COLUMNS + " FROM katharsis_account_links WHERE discord_user_id = ?";

    private static final String INSERT_LINK =
            "INSERT INTO katharsis_account_links (" + LINK_COLUMNS + ") VALUES (?, ?, ?, ?, ?)";

    /** Clears expired codes, and the user's previous code, before issuing a new one. */
    private static final String DELETE_STALE_CODES = """
            DELETE FROM katharsis_link_codes
            WHERE expires_at <= ? OR discord_user_id = ?""";

    private static final String INSERT_CODE = """
            INSERT INTO katharsis_link_codes (code, discord_user_id, discord_username, expires_at)
            VALUES (?, ?, ?, ?)""";

    private static final String SELECT_LIVE_CODE = """
            SELECT code, discord_user_id, discord_username, expires_at
            FROM katharsis_link_codes
            WHERE code = ? AND expires_at > ?""";

    private static final String DELETE_CODE = """
            DELETE FROM katharsis_link_codes
            WHERE code = ? AND discord_user_id = ?""";

    /** SQLState for a unique/primary key violation, shared by PostgreSQL and H2. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DatabaseManager database;

    public AccountLinkRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing tables if they do not exist yet. */
    public void createTables() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_LINKS_TABLE);
            statement.execute(CREATE_CODES_TABLE);
        }
    }

    /** The link of a Minecraft account, if it has one. */
    public Optional<AccountLink> findByPlayer(UUID playerUuid) throws SQLException {
        try (Connection connection = database.getConnection()) {
            return findLink(connection, SELECT_LINK_BY_PLAYER, statement -> statement.setObject(1, playerUuid));
        }
    }

    /** The link of a Discord account, if it has one. */
    public Optional<AccountLink> findByDiscordUser(long discordUserId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            return findLink(connection, SELECT_LINK_BY_DISCORD_USER, statement -> statement.setLong(1, discordUserId));
        }
    }

    /**
     * Stores {@code code} as its user's only outstanding code, dropping their
     * previous one and every expired code.
     *
     * @return {@code false} if the code string is already taken, in which case
     *         nothing changed and the caller should try another
     */
    public boolean replaceCode(LinkCode code, Instant now) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(DELETE_STALE_CODES)) {
                    delete.setObject(1, utc(now));
                    delete.setLong(2, code.discordUserId());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(INSERT_CODE)) {
                    insert.setString(1, code.code());
                    insert.setLong(2, code.discordUserId());
                    insert.setString(3, code.discordUsername());
                    insert.setObject(4, utc(code.expiresAt()));
                    insert.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    return false;
                }
                throw e;
            }
        }
    }

    /**
     * Uses up {@code code} to link {@code playerUuid} to the code's Discord
     * account, in one transaction: the code is only spent if the link is made,
     * and two players racing for the same code cannot both win.
     */
    public RedeemResult redeem(String code, UUID playerUuid, String playerName, Instant now) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                RedeemResult result = redeem(connection, code, playerUuid, playerName, now);
                if (result instanceof RedeemResult.Linked) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException e) {
                connection.rollback();
                if (!UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
        // Lost a race with another link for one of the two accounts. PostgreSQL
        // aborts the transaction on the violation, so look again from scratch.
        Optional<AccountLink> existing = findByPlayer(playerUuid);
        return existing.<RedeemResult>map(RedeemResult.PlayerAlreadyLinked::new)
                .orElseGet(RedeemResult.DiscordAlreadyLinked::new);
    }

    private RedeemResult redeem(Connection connection, String code, UUID playerUuid, String playerName, Instant now)
            throws SQLException {
        LinkCode live;
        try (PreparedStatement select = connection.prepareStatement(SELECT_LIVE_CODE)) {
            select.setString(1, code);
            select.setObject(2, utc(now));
            try (ResultSet results = select.executeQuery()) {
                if (!results.next()) {
                    return new RedeemResult.InvalidCode();
                }
                live = new LinkCode(results.getString("code"), results.getLong("discord_user_id"),
                        results.getString("discord_username"),
                        results.getObject("expires_at", OffsetDateTime.class).toInstant());
            }
        }

        Optional<AccountLink> existing = findLink(connection, SELECT_LINK_BY_PLAYER,
                statement -> statement.setObject(1, playerUuid));
        if (existing.isPresent()) {
            return new RedeemResult.PlayerAlreadyLinked(existing.get());
        }
        if (findLink(connection, SELECT_LINK_BY_DISCORD_USER,
                statement -> statement.setLong(1, live.discordUserId())).isPresent()) {
            return new RedeemResult.DiscordAlreadyLinked();
        }

        try (PreparedStatement delete = connection.prepareStatement(DELETE_CODE)) {
            delete.setString(1, live.code());
            delete.setLong(2, live.discordUserId());
            if (delete.executeUpdate() == 0) {
                // Someone else redeemed or replaced it since the select.
                return new RedeemResult.InvalidCode();
            }
        }

        AccountLink link = new AccountLink(playerUuid, playerName, live.discordUserId(), live.discordUsername(), now);
        try (PreparedStatement insert = connection.prepareStatement(INSERT_LINK)) {
            insert.setObject(1, link.playerUuid());
            insert.setString(2, link.playerName());
            insert.setLong(3, link.discordUserId());
            insert.setString(4, link.discordUsername());
            insert.setObject(5, utc(link.linkedAt()));
            insert.executeUpdate();
        }
        return new RedeemResult.Linked(link);
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static Optional<AccountLink> findLink(Connection connection, String sql, Binder binder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AccountLink(
                        results.getObject("player_uuid", UUID.class),
                        results.getString("player_name"),
                        results.getLong("discord_user_id"),
                        results.getString("discord_username"),
                        results.getObject("linked_at", OffsetDateTime.class).toInstant()));
            }
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
