package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.DatabaseConfig;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;

import java.nio.file.Path;
import java.sql.SQLException;

/** Opens a throwaway H2 database in a test's temporary directory. */
public final class DatabaseTestSupport {

    private DatabaseTestSupport() {
    }

    public static DatabaseManager started(Path directory) throws ConfigurateException, SQLException {
        ConfigManager config = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        config.load();
        return started(directory, config.config().database());
    }

    public static DatabaseManager started(Path directory, DatabaseConfig database) throws SQLException {
        DatabaseManager manager = new DatabaseManager(LoggerFactory.getLogger("test"), directory);
        manager.start(database);
        return manager;
    }
}
