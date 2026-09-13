package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.DatabaseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {

    private DatabaseManager database;

    @AfterEach
    void closePool() {
        if (database != null) {
            database.close();
        }
    }

    private DatabaseConfig configIn(Path directory) throws ConfigurateException {
        ConfigManager config = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        config.load();
        return config.config().database();
    }

    @Test
    @DisplayName("opens an H2 pool and hands out connections")
    void opensH2Pool(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);

        assertTrue(database.isAvailable());
        try (Connection connection = database.getConnection()) {
            assertFalse(connection.isClosed());
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        }
        assertTrue(Files.exists(directory.resolve("katharsis.mv.db")), "the file database was created");
    }

    @Test
    @DisplayName("closing makes the pool unavailable and guards getConnection")
    void closeGuardsAccess(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        database.close();

        assertFalse(database.isAvailable());
        SQLException thrown = assertThrows(SQLException.class, database::getConnection);
        assertEquals("The database connection pool is not running", thrown.getMessage());
    }

    @Test
    @DisplayName("closing twice is harmless")
    void closeIsIdempotent(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        database.close();
        database.close();

        assertFalse(database.isAvailable());
    }

    @Test
    @DisplayName("starting an already running pool is refused")
    void doubleStartRefused(@TempDir Path directory) throws Exception {
        DatabaseConfig config = configIn(directory);
        database = DatabaseTestSupport.started(directory, config);

        assertThrows(IllegalStateException.class, () -> database.start(config));
    }

    @Test
    @DisplayName("an unreachable server surfaces as SQLException, not Hikari's unchecked one")
    void unreachableServerThrowsSqlException(@TempDir Path directory) throws ConfigurateException, IOException {
        // Port 1 is reserved, so the connection is refused immediately.
        Files.writeString(directory.resolve("config.yml"), """
                database:
                  type: postgresql
                  postgresql:
                    host: 127.0.0.1
                    port: 1
                  pool:
                    connection-timeout-ms: 1000
                """);
        DatabaseConfig config = configIn(directory);
        database = new DatabaseManager(LoggerFactory.getLogger("test"), directory);

        assertThrows(SQLException.class, () -> database.start(config));
        assertFalse(database.isAvailable(), "a failed start must not leave a half-open pool");
    }
}
