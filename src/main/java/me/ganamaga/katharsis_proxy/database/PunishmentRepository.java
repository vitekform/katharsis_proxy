package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores {@link Punishment}s and answers which of them are in force.
 *
 * <p>Nothing here expires rows: a punishment is active while it is lasting,
 * unrevoked, and its {@code expires_at} is later than the {@code now} the
 * caller passes in. Taking the time from the caller rather than the database's
 * {@code NOW()} keeps the proxy's clock the only one that matters.</p>
 */
public final class PunishmentRepository {

    /**
     * No foreign keys: a punishment may name an address or an account the
     * network has never seen, and it must survive whatever happens to the
     * player tables.
     */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS katharsis_punishments (
                id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                type            VARCHAR(8)               NOT NULL,
                target_uuid     UUID,
                target_name     VARCHAR(64)              NOT NULL,
                target_ip       VARCHAR(45),
                scope_kind      VARCHAR(8)               NOT NULL,
                scope_name      VARCHAR(64),
                reason          VARCHAR(512)             NOT NULL,
                template        VARCHAR(64),
                issuer_uuid     UUID,
                issuer_name     VARCHAR(64)              NOT NULL,
                issuer_source   VARCHAR(12)              NOT NULL,
                silent          BOOLEAN                  NOT NULL DEFAULT FALSE,
                parent_id       BIGINT,
                created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
                expires_at      TIMESTAMP WITH TIME ZONE,
                revoked_at      TIMESTAMP WITH TIME ZONE,
                revoked_by_uuid UUID,
                revoked_by_name VARCHAR(64),
                revoked_by_source VARCHAR(12),
                revoke_reason   VARCHAR(512),
                CHECK (target_uuid IS NOT NULL OR target_ip IS NOT NULL)
            )""";

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS katharsis_punishments_uuid ON katharsis_punishments (target_uuid, type)",
            "CREATE INDEX IF NOT EXISTS katharsis_punishments_ip ON katharsis_punishments (target_ip, type)",
            "CREATE INDEX IF NOT EXISTS katharsis_punishments_created ON katharsis_punishments (created_at)"
    };

    private static final String COLUMNS = """
            id, type, target_uuid, target_name, target_ip, scope_kind, scope_name, reason, template,
            issuer_uuid, issuer_name, issuer_source, silent, parent_id, created_at, expires_at,
            revoked_at, revoked_by_uuid, revoked_by_name, revoked_by_source, revoke_reason""";

    private static final String INSERT = """
            INSERT INTO katharsis_punishments
                (type, target_uuid, target_name, target_ip, scope_kind, scope_name, reason, template,
                 issuer_uuid, issuer_name, issuer_source, silent, parent_id, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM katharsis_punishments WHERE id = ?";

    /** Lasting, unrevoked, and not run out; the target clause is filled in per query. */
    private static final String ACTIVE = """
            type <> 'KICK' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)""";

    private static final String REVOKE = """
            UPDATE katharsis_punishments
            SET revoked_at = ?, revoked_by_uuid = ?, revoked_by_name = ?, revoked_by_source = ?, revoke_reason = ?
            WHERE id = ? AND revoked_at IS NULL""";

    private static final String COUNT_BY_TEMPLATE = """
            SELECT COUNT(*) FROM katharsis_punishments
            WHERE target_uuid = ? AND template = ? AND revoked_at IS NULL""";

    private final DatabaseManager database;

    public PunishmentRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the backing table and its indexes if they do not exist yet. */
    public void createTable() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                statement.execute(index);
            }
        }
    }

    /**
     * Stores a new punishment.
     *
     * @return the punishment with the id it was stored under
     * @throws IllegalArgumentException if it already has an id or is already revoked
     */
    public Punishment insert(Punishment punishment) throws SQLException {
        if (punishment.id() != 0 || punishment.isRevoked()) {
            throw new IllegalArgumentException("only a new, unrevoked punishment can be inserted");
        }
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT, new String[]{"id"})) {
            statement.setString(1, punishment.type().name());
            statement.setObject(2, punishment.targetUuid());
            statement.setString(3, punishment.targetName());
            statement.setString(4, punishment.targetIp());
            statement.setString(5, punishment.scope().kind().key());
            statement.setString(6, punishment.scope().name());
            statement.setString(7, punishment.reason());
            statement.setString(8, punishment.template());
            statement.setObject(9, punishment.issuer().uuid());
            statement.setString(10, punishment.issuer().name());
            statement.setString(11, punishment.issuer().source().key());
            statement.setBoolean(12, punishment.silent());
            if (punishment.parentId() == null) {
                statement.setNull(13, Types.BIGINT);
            } else {
                statement.setLong(13, punishment.parentId());
            }
            statement.setObject(14, utc(punishment.createdAt()));
            statement.setObject(15, utc(punishment.expiresAt()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("the database returned no id for the new punishment");
                }
                return punishment.withId(keys.getLong(1));
            }
        }
    }

    /** Looks up a punishment by id, revoked or not. */
    public Optional<Punishment> find(long id) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results)) : Optional.empty();
            }
        }
    }

    /**
     * Every punishment in force at {@code now} against the account
     * {@code uuid} or the address {@code ip}, newest first. Either may be
     * {@code null}, not both.
     */
    public List<Punishment> findActive(UUID uuid, String ip, Instant now) throws SQLException {
        Target target = Target.of(uuid, ip);
        String sql = "SELECT " + COLUMNS + " FROM katharsis_punishments WHERE " + target.clause()
                + " AND " + ACTIVE + " ORDER BY created_at DESC, id DESC";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = target.bind(statement, 1);
            statement.setObject(next, utc(now));
            return readAll(statement);
        }
    }

    /**
     * One page of everything issued against the account {@code uuid} or the
     * address {@code ip}, revoked and expired included, newest first.
     */
    public List<Punishment> findHistory(UUID uuid, String ip, int limit, int offset) throws SQLException {
        Target target = Target.of(uuid, ip);
        String sql = "SELECT " + COLUMNS + " FROM katharsis_punishments WHERE " + target.clause()
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = target.bind(statement, 1);
            statement.setInt(next, limit);
            statement.setInt(next + 1, offset);
            return readAll(statement);
        }
    }

    /** How many punishments {@link #findHistory} can page through. */
    public int countHistory(UUID uuid, String ip) throws SQLException {
        Target target = Target.of(uuid, ip);
        String sql = "SELECT COUNT(*) FROM katharsis_punishments WHERE " + target.clause();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            target.bind(statement, 1);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    /**
     * How many unrevoked punishments the account {@code uuid} has had from
     * {@code template}, expired ones included; this picks the rung of the
     * template's ladder. A lifted punishment, e.g. one won on appeal, does not
     * count against the player.
     */
    public int countByTemplate(UUID uuid, String template) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(COUNT_BY_TEMPLATE)) {
            statement.setObject(1, uuid);
            statement.setString(2, template);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    /**
     * Lifts a punishment.
     *
     * @return {@code true} if it was lifted, {@code false} if there is no such
     *         punishment or it had already been lifted
     */
    public boolean revoke(long id, Punishment.Revocation revocation) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(REVOKE)) {
            statement.setObject(1, utc(revocation.at()));
            statement.setObject(2, revocation.by().uuid());
            statement.setString(3, revocation.by().name());
            statement.setString(4, revocation.by().source().key());
            statement.setString(5, revocation.reason());
            statement.setLong(6, id);
            return statement.executeUpdate() > 0;
        }
    }

    private static List<Punishment> readAll(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<Punishment> punishments = new ArrayList<>();
            while (results.next()) {
                punishments.add(read(results));
            }
            return punishments;
        }
    }

    private static Punishment read(ResultSet results) throws SQLException {
        String scopeName = results.getString("scope_name");
        PunishmentScope scope = new PunishmentScope(
                PunishmentScope.Kind.fromKey(results.getString("scope_kind")), scopeName);
        long parent = results.getLong("parent_id");
        Long parentId = results.wasNull() ? null : parent;
        Instant revokedAt = instant(results, "revoked_at");
        Punishment.Revocation revocation = revokedAt == null ? null : new Punishment.Revocation(
                revokedAt,
                new IssuerRef(results.getObject("revoked_by_uuid", UUID.class),
                        results.getString("revoked_by_name"),
                        IssuerRef.Source.fromKey(results.getString("revoked_by_source"))),
                results.getString("revoke_reason"));
        return new Punishment(
                results.getLong("id"),
                PunishmentType.valueOf(results.getString("type")),
                results.getObject("target_uuid", UUID.class),
                results.getString("target_name"),
                results.getString("target_ip"),
                scope,
                results.getString("reason"),
                results.getString("template"),
                new IssuerRef(results.getObject("issuer_uuid", UUID.class),
                        results.getString("issuer_name"),
                        IssuerRef.Source.fromKey(results.getString("issuer_source"))),
                results.getBoolean("silent"),
                parentId,
                instant(results, "created_at"),
                instant(results, "expires_at"),
                revocation);
    }

    private static Instant instant(ResultSet results, String column) throws SQLException {
        OffsetDateTime value = results.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * The "this account or this address" part of a query. Written out per
     * case rather than binding a {@code null} uuid, which PostgreSQL and H2
     * disagree on how to type.
     */
    private record Target(UUID uuid, String ip) {

        static Target of(UUID uuid, String ip) {
            if (uuid == null && ip == null) {
                throw new IllegalArgumentException("need an account, an address, or both");
            }
            return new Target(uuid, ip);
        }

        String clause() {
            if (uuid != null && ip != null) {
                return "(target_uuid = ? OR target_ip = ?)";
            }
            return uuid != null ? "target_uuid = ?" : "target_ip = ?";
        }

        /** Binds the clause's parameters from {@code index}; returns the next free index. */
        int bind(PreparedStatement statement, int index) throws SQLException {
            if (uuid != null) {
                statement.setObject(index++, uuid);
            }
            if (ip != null) {
                statement.setString(index++, ip);
            }
            return index;
        }
    }
}
