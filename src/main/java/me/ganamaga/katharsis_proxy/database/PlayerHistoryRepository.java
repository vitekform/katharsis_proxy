package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.AuthAccount;
import me.ganamaga.katharsis_proxy.entity.PlayerHistory;
import me.ganamaga.katharsis_proxy.entity.PlayerIp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which accounts have joined, under which names, and from which
 * addresses.
 *
 * <p>This is what lets staff punish a player who is offline by name, ban the
 * address they last used, and find accounts that share an address.</p>
 */
public final class PlayerHistoryRepository {

    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS katharsis_player_history (
                uuid       UUID                     NOT NULL PRIMARY KEY,
                name       VARCHAR(32)              NOT NULL,
                name_key   VARCHAR(32)              NOT NULL,
                first_seen TIMESTAMP WITH TIME ZONE NOT NULL,
                last_seen  TIMESTAMP WITH TIME ZONE NOT NULL
            )""";

    private static final String CREATE_HISTORY_INDEX = """
            CREATE INDEX IF NOT EXISTS katharsis_player_history_name
            ON katharsis_player_history (name_key, last_seen)""";

    private static final String CREATE_IPS = """
            CREATE TABLE IF NOT EXISTS katharsis_player_ips (
                uuid       UUID                     NOT NULL,
                ip         VARCHAR(45)              NOT NULL,
                first_seen TIMESTAMP WITH TIME ZONE NOT NULL,
                last_seen  TIMESTAMP WITH TIME ZONE NOT NULL,
                logins     INTEGER                  NOT NULL DEFAULT 1,
                PRIMARY KEY (uuid, ip)
            )""";

    private static final String CREATE_IPS_INDEX = """
            CREATE INDEX IF NOT EXISTS katharsis_player_ips_ip
            ON katharsis_player_ips (ip)""";

    private static final String UPDATE_HISTORY = """
            UPDATE katharsis_player_history
            SET name = ?, name_key = ?, last_seen = ?
            WHERE uuid = ?""";

    private static final String INSERT_HISTORY = """
            INSERT INTO katharsis_player_history (uuid, name, name_key, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?)""";

    private static final String UPDATE_IP = """
            UPDATE katharsis_player_ips
            SET last_seen = ?, logins = logins + 1
            WHERE uuid = ? AND ip = ?""";

    private static final String INSERT_IP = """
            INSERT INTO katharsis_player_ips (uuid, ip, first_seen, last_seen, logins)
            VALUES (?, ?, ?, ?, 1)""";

    private static final String SELECT_BY_UUID = """
            SELECT uuid, name, first_seen, last_seen
            FROM katharsis_player_history
            WHERE uuid = ?""";

    private static final String SELECT_BY_NAME = """
            SELECT uuid, name, first_seen, last_seen
            FROM katharsis_player_history
            WHERE name_key = ?
            ORDER BY last_seen DESC""";

    private static final String SELECT_IPS = """
            SELECT uuid, ip, first_seen, last_seen, logins
            FROM katharsis_player_ips
            WHERE uuid = ?
            ORDER BY last_seen DESC""";

    private static final String SELECT_BY_IP = """
            SELECT uuid, ip, first_seen, last_seen, logins
            FROM katharsis_player_ips
            WHERE ip = ? AND last_seen >= ?
            ORDER BY last_seen DESC""";

    /** SQLState for a unique/primary key violation, shared by PostgreSQL and H2. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DatabaseManager database;

    public PlayerHistoryRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing tables and their indexes if they do not exist yet. */
    public void createTables() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_HISTORY);
            statement.execute(CREATE_HISTORY_INDEX);
            statement.execute(CREATE_IPS);
            statement.execute(CREATE_IPS_INDEX);
        }
    }

    /**
     * Records that {@code uuid} joined as {@code name}, from {@code ip} if it is
     * known.
     *
     * @throws IllegalArgumentException if the name or address does not fit its column
     */
    public void recordJoin(UUID uuid, String name, String ip, Instant now) throws SQLException {
        new PlayerHistory(uuid, name, now, now); // applies the column's length rule
        if (ip != null) {
            new PlayerIp(uuid, ip, now, now, 1);
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        try (Connection connection = database.getConnection()) {
            upsert(connection,
                    update -> {
                        update.setString(1, name);
                        update.setString(2, AuthAccount.key(name));
                        update.setObject(3, at);
                        update.setObject(4, uuid);
                    }, UPDATE_HISTORY,
                    insert -> {
                        insert.setObject(1, uuid);
                        insert.setString(2, name);
                        insert.setString(3, AuthAccount.key(name));
                        insert.setObject(4, at);
                        insert.setObject(5, at);
                    }, INSERT_HISTORY);
            if (ip != null) {
                upsert(connection,
                        update -> {
                            update.setObject(1, at);
                            update.setObject(2, uuid);
                            update.setString(3, ip);
                        }, UPDATE_IP,
                        insert -> {
                            insert.setObject(1, uuid);
                            insert.setString(2, ip);
                            insert.setObject(3, at);
                            insert.setObject(4, at);
                        }, INSERT_IP);
            }
        }
    }

    /**
     * Updates the row if there is one, otherwise inserts it. Written without
     * {@code ON CONFLICT}, which H2 does not understand; if another join
     * inserts the same row in between, the insert fails as a duplicate and the
     * update is tried once more.
     */
    private static void upsert(Connection connection, Binder bindUpdate, String update,
                               Binder bindInsert, String insert) throws SQLException {
        if (execute(connection, update, bindUpdate) > 0) {
            return;
        }
        try {
            execute(connection, insert, bindInsert);
        } catch (SQLException e) {
            if (!UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw e;
            }
            execute(connection, update, bindUpdate);
        }
    }

    private static int execute(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    /** Looks up an account by uuid. */
    public Optional<PlayerHistory> find(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_UUID)) {
            statement.setObject(1, uuid);
            List<PlayerHistory> found = readHistory(statement);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    /**
     * Every account that last joined under {@code name}, regardless of case,
     * most recently seen first.
     */
    public List<PlayerHistory> findByName(String name) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_NAME)) {
            statement.setString(1, AuthAccount.key(name));
            return readHistory(statement);
        }
    }

    /** The most recently seen account that last joined under {@code name}. */
    public Optional<PlayerHistory> findLatestByName(String name) throws SQLException {
        List<PlayerHistory> found = findByName(name);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Every address {@code uuid} has joined from, most recent first. */
    public List<PlayerIp> ips(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_IPS)) {
            statement.setObject(1, uuid);
            return readIps(statement);
        }
    }

    /** The address {@code uuid} last joined from. */
    public Optional<String> lastIp(UUID uuid) throws SQLException {
        List<PlayerIp> ips = ips(uuid);
        return ips.isEmpty() ? Optional.empty() : Optional.of(ips.get(0).ip());
    }

    /** Every account that joined from {@code ip} at or after {@code since}, most recent first. */
    public List<PlayerIp> accountsOn(String ip, Instant since) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_IP)) {
            statement.setString(1, ip);
            statement.setObject(2, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
            return readIps(statement);
        }
    }

    private static List<PlayerHistory> readHistory(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<PlayerHistory> found = new ArrayList<>();
            while (results.next()) {
                found.add(new PlayerHistory(
                        results.getObject("uuid", UUID.class),
                        results.getString("name"),
                        results.getObject("first_seen", OffsetDateTime.class).toInstant(),
                        results.getObject("last_seen", OffsetDateTime.class).toInstant()));
            }
            return found;
        }
    }

    private static List<PlayerIp> readIps(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<PlayerIp> found = new ArrayList<>();
            while (results.next()) {
                found.add(new PlayerIp(
                        results.getObject("uuid", UUID.class),
                        results.getString("ip"),
                        results.getObject("first_seen", OffsetDateTime.class).toInstant(),
                        results.getObject("last_seen", OffsetDateTime.class).toInstant(),
                        results.getInt("logins")));
            }
            return found;
        }
    }
}
