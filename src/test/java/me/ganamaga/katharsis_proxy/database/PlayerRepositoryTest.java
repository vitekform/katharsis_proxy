package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRepositoryTest {

    private Path directory;
    private DatabaseManager database;
    private PlayerRepository players;

    @BeforeEach
    void openDatabase(@TempDir Path directory) throws Exception {
        this.directory = directory;
        this.database = DatabaseTestSupport.started(directory);
        this.players = new PlayerRepository(database);
        players.createTable();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    @DisplayName("createTable can run again on an existing schema")
    void createTableIsIdempotent() throws SQLException {
        players.createTable();
        players.createTable();
    }

    @Test
    @DisplayName("a first join inserts the player")
    void firstJoinInserts() throws SQLException {
        UUID id = UUID.randomUUID();

        assertTrue(players.createIfAbsent(id, "en_US"));
        Player stored = players.find(id).orElseThrow();
        assertEquals(id, stored.uuid(), "the uuid round-trips through the UUID column");
        assertEquals("en_US", stored.language());
    }

    @Test
    @DisplayName("a second join neither duplicates nor overwrites")
    void secondJoinIsANoOp() throws SQLException {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        assertFalse(players.createIfAbsent(id, "cs_CZ"), "no row was inserted");
        assertEquals("en_US", players.find(id).orElseThrow().language(), "the stored language is untouched");
    }

    @Test
    @DisplayName("an unknown player is simply absent")
    void unknownPlayerIsAbsent() throws SQLException {
        assertTrue(players.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("updateLanguage changes an existing row")
    void updateLanguage() throws SQLException {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");

        assertTrue(players.updateLanguage(id, "cs_CZ"));
        assertEquals("cs_CZ", players.find(id).orElseThrow().language());
    }

    @Test
    @DisplayName("updateLanguage reports when there is no such row")
    void updateUnknownPlayer() throws SQLException {
        assertFalse(players.updateLanguage(UUID.randomUUID(), "cs_CZ"));
    }

    @Test
    @DisplayName("a language too wide for the column is refused before it reaches SQL")
    void overlongLanguageRefused() {
        UUID id = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> players.createIfAbsent(id, "en_US_X"));
        assertThrows(IllegalArgumentException.class, () -> players.updateLanguage(id, "en_US_X"));
    }

    @Test
    @DisplayName("Discord chat starts hidden and updateDiscordChat changes it")
    void discordChat() throws SQLException {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "en_US");
        assertFalse(players.find(id).orElseThrow().discordChat(), "hidden until the player turns it on");

        assertTrue(players.updateDiscordChat(id, true));
        Player stored = players.find(id).orElseThrow();
        assertTrue(stored.discordChat());
        assertEquals("en_US", stored.language(), "the language is untouched");

        assertTrue(players.updateDiscordChat(id, false));
        assertFalse(players.find(id).orElseThrow().discordChat());
        assertFalse(players.updateDiscordChat(UUID.randomUUID(), true), "no such row");
    }

    @Test
    @DisplayName("a table from before Discord chat settings gains the column, hidden for everyone")
    void migratesAnOlderTable() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE katharsis_players");
            statement.execute("""
                    CREATE TABLE katharsis_players (
                        uuid     UUID        NOT NULL PRIMARY KEY,
                        language VARCHAR(5)  NOT NULL DEFAULT 'en_US'
                    )""");
            statement.execute("INSERT INTO katharsis_players (uuid, language) VALUES ('" + id + "', 'cs_CZ')");
        }

        players.createTable();

        Player stored = players.find(id).orElseThrow();
        assertEquals("cs_CZ", stored.language());
        assertFalse(stored.discordChat());
    }

    @Test
    @DisplayName("rows survive a restart of the pool")
    void rowsSurviveRestart() throws Exception {
        UUID id = UUID.randomUUID();
        players.createIfAbsent(id, "cs_CZ");
        database.close();

        database = DatabaseTestSupport.started(directory);
        assertEquals("cs_CZ", new PlayerRepository(database).find(id).orElseThrow().language());
    }

    @Test
    @DisplayName("concurrent first joins insert exactly one row")
    void concurrentFirstJoins() throws Exception {
        UUID id = UUID.randomUUID();
        int threads = 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        var inserts = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.atomic.AtomicInteger();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (players.createIfAbsent(id, "en_US")) {
                        inserts.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(0, failures.get(), "a duplicate-key race must be swallowed, not thrown");
        assertEquals(1, inserts.get(), "exactly one caller should report an insert");
        assertTrue(players.find(id).isPresent());
    }
}
