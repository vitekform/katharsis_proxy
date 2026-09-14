package me.ganamaga.katharsis_proxy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {

    private ConfigManager managerIn(Path directory) {
        return new ConfigManager(LoggerFactory.getLogger(ConfigManagerTest.class), directory);
    }

    @Test
    @DisplayName("writes the bundled default on first run, comments included")
    void writesDefaultConfig(@TempDir Path directory) throws ConfigurateException, IOException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        Path config = directory.resolve("config.yml");
        assertTrue(Files.exists(config));

        String written = Files.readString(config);
        assertTrue(written.contains("# Katharsis proxy configuration."), "header comment");
        assertTrue(written.contains("#   h2         - embedded file database"), "inline comments survive");
        assertTrue(written.contains("type: h2"));
    }

    @Test
    @DisplayName("defaults to the embedded H2 backend")
    void defaultsToH2(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        DatabaseConfig database = manager.config().database();
        assertEquals(DatabaseType.H2, database.type());
        assertEquals("sa", database.username());
        assertEquals("", database.password());
        assertEquals("en_US", manager.config().defaultLanguage());
        assertTrue(database.jdbcUrl(directory).startsWith("jdbc:h2:file:"));
        assertTrue(database.jdbcUrl(directory).contains("MODE=PostgreSQL"),
                "H2 runs in PostgreSQL compatibility mode so one SQL dialect serves both");
    }

    @Test
    @DisplayName("a partial file overrides only what it names")
    void partialOverride(@TempDir Path directory) throws ConfigurateException, IOException {
        Files.writeString(directory.resolve("config.yml"), """
                database:
                  type: postgresql
                  postgresql:
                    host: db.example.com
                    port: 6543
                    database: katharsis_prod
                    username: kath
                    password: 'hunter2'
                    ssl-mode: require
                  pool:
                    maximum-pool-size: 25
                default-language: cs_CZ
                """);

        ConfigManager manager = managerIn(directory);
        manager.load();
        DatabaseConfig database = manager.config().database();

        assertEquals(DatabaseType.POSTGRESQL, database.type());
        assertEquals("jdbc:postgresql://db.example.com:6543/katharsis_prod?sslmode=require",
                database.jdbcUrl(directory));
        assertEquals("kath", database.username());
        assertEquals("hunter2", database.password());
        assertEquals("cs_CZ", manager.config().defaultLanguage());
        assertEquals(25, database.pool().maximumPoolSize(), "overridden");
        assertEquals(2, database.pool().minimumIdle(), "not named, so the default is kept");
        assertEquals("katharsis", database.h2().file(), "unused section keeps its default");
    }

    @Test
    @DisplayName("every motd key in the shipped config.yml maps onto a field")
    void shippedMotdKeysAllMap(@TempDir Path directory) throws ConfigurateException {
        managerIn(directory).load();

        // Configurate ignores keys it does not recognise, so a typo in the
        // shipped file would silently do nothing. Compare the keys the file
        // ships against the ones the mapper would write for the same object.
        Set<Object> shipped = YamlConfigurationLoader.builder()
                .path(directory.resolve("config.yml"))
                .build()
                .load()
                .node("motd")
                .childrenMap()
                .keySet();

        CommentedConfigurationNode mapped = CommentedConfigurationNode.root();
        mapped.set(MotdConfig.class, new MotdConfig());

        assertEquals(mapped.childrenMap().keySet(), shipped);
    }

    @Test
    @DisplayName("the motd section is off by default, so velocity.toml keeps the ping")
    void motdDefaultsToOff(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        MotdConfig motd = manager.config().motd();
        assertFalse(motd.enabled());
        assertEquals(MotdMode.RANDOM, motd.mode());
        assertEquals(MotdFallback.CONFIG, motd.fallback());
        assertEquals(773, motd.minProtocol());
        assertEquals(774, motd.maxProtocol());
    }

    @Test
    @DisplayName("an existing file is never rewritten, so admin comments survive")
    void doesNotRewriteExistingFile(@TempDir Path directory) throws ConfigurateException, IOException {
        Path config = directory.resolve("config.yml");
        String original = """
                # my own note
                default-language: cs_CZ
                """;
        Files.writeString(config, original);

        managerIn(directory).load();

        assertEquals(original, Files.readString(config));
    }

    @Test
    @DisplayName("reloading picks up edits")
    void reloadPicksUpEdits(@TempDir Path directory) throws ConfigurateException, IOException {
        ConfigManager manager = managerIn(directory);
        manager.load();
        assertEquals("en_US", manager.config().defaultLanguage());

        Files.writeString(directory.resolve("config.yml"), "default-language: cs_CZ\n");
        manager.load();

        assertEquals("cs_CZ", manager.config().defaultLanguage());
    }
}
