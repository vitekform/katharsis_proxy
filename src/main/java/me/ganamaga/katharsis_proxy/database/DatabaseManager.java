package me.ganamaga.katharsis_proxy.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.ganamaga.katharsis_proxy.config.DatabaseConfig;
import me.ganamaga.katharsis_proxy.config.DatabaseType;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns the HikariCP pool that backs every database access in the plugin.
 */
public final class DatabaseManager {

    /*
     * These names are rewritten to the shaded packages by maven-shade-plugin,
     * which relocates string constants matching a relocation pattern.
     */
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";
    private static final String H2_DRIVER = "org.h2.Driver";

    private final Logger logger;
    private final Path dataDirectory;

    private volatile HikariDataSource dataSource;

    public DatabaseManager(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Opens the pool and verifies that a connection can actually be obtained.
     *
     * @throws SQLException if the database cannot be reached
     */
    public void start(DatabaseConfig config) throws SQLException {
        if (dataSource != null) {
            throw new IllegalStateException("The connection pool is already running");
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("katharsis-pool");
        hikari.setDriverClassName(driverClassName(config.type()));
        hikari.setJdbcUrl(config.jdbcUrl(dataDirectory));
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.pool().maximumPoolSize());
        hikari.setMinimumIdle(config.pool().minimumIdle());
        hikari.setConnectionTimeout(config.pool().connectionTimeoutMs());
        hikari.setIdleTimeout(config.pool().idleTimeoutMs());
        hikari.setMaxLifetime(config.pool().maxLifetimeMs());

        HikariDataSource created;
        try {
            created = new HikariDataSource(hikari);
        } catch (RuntimeException e) {
            // Hikari fails fast by throwing PoolInitializationException, an
            // unchecked exception wrapping the real cause.
            if (e.getCause() instanceof SQLException cause) {
                throw cause;
            }
            throw new SQLException("Could not initialise the connection pool", e);
        }

        try (Connection connection = created.getConnection()) {
            logger.info("Connected to {} (server version {}).",
                    config.describe(dataDirectory),
                    connection.getMetaData().getDatabaseProductVersion());
        } catch (SQLException e) {
            created.close();
            throw e;
        }
        this.dataSource = created;
    }

    /** Whether the pool is up and can hand out connections. */
    public boolean isAvailable() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed();
    }

    /**
     * Borrows a connection from the pool. Always close it, ideally with
     * try-with-resources, so it returns to the pool.
     */
    public Connection getConnection() throws SQLException {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            throw new SQLException("The database connection pool is not running");
        }
        return current.getConnection();
    }

    /** Closes the pool. Safe to call when it was never started. */
    public void close() {
        HikariDataSource current = dataSource;
        dataSource = null;
        if (current != null && !current.isClosed()) {
            current.close();
            logger.info("Closed the database connection pool.");
        }
    }

    private static String driverClassName(DatabaseType type) {
        return switch (type) {
            case H2 -> H2_DRIVER;
            case POSTGRESQL -> POSTGRES_DRIVER;
        };
    }
}
