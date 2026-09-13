package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.nio.file.Path;

/**
 * The {@code database} section of {@code config.yml}: which backend to use, the
 * settings for each, and the pool shared by both.
 */
@ConfigSerializable
public class DatabaseConfig {

    /**
     * H2 speaks its own SQL dialect by default. Running it in PostgreSQL
     * compatibility mode lets both backends share one set of statements.
     */
    private static final String H2_COMPATIBILITY_OPTIONS = ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

    private DatabaseType type = DatabaseType.H2;
    private H2Config h2 = new H2Config();
    private PostgresConfig postgresql = new PostgresConfig();
    private PoolConfig pool = new PoolConfig();

    public DatabaseType type() {
        return type;
    }

    public H2Config h2() {
        return h2;
    }

    public PostgresConfig postgresql() {
        return postgresql;
    }

    public PoolConfig pool() {
        return pool;
    }

    /**
     * JDBC URL for the selected backend.
     *
     * @param dataDirectory the plugin data directory, which H2 database files
     *                      are resolved against
     */
    public String jdbcUrl(Path dataDirectory) {
        return switch (type) {
            case H2 -> "jdbc:h2:file:" + dataDirectory.resolve(h2.file()).toAbsolutePath()
                    + H2_COMPATIBILITY_OPTIONS;
            case POSTGRESQL -> postgresql.jdbcUrl();
        };
    }

    /** User to authenticate as. H2 file databases are created with the default user. */
    public String username() {
        return switch (type) {
            case H2 -> "sa";
            case POSTGRESQL -> postgresql.username();
        };
    }

    /** Password for {@link #username()}. */
    public String password() {
        return switch (type) {
            case H2 -> "";
            case POSTGRESQL -> postgresql.password();
        };
    }

    /** Human-readable description of the target, for log messages. */
    public String describe(Path dataDirectory) {
        return switch (type) {
            case H2 -> "H2 file database at " + dataDirectory.resolve(h2.file()).toAbsolutePath();
            case POSTGRESQL -> "PostgreSQL at " + postgresql.host() + ":" + postgresql.port()
                    + "/" + postgresql.database();
        };
    }
}
