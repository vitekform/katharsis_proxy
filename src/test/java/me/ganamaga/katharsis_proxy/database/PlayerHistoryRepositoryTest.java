package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.PlayerHistory;
import me.ganamaga.katharsis_proxy.entity.PlayerIp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerHistoryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private DatabaseManager database;
    private PlayerHistoryRepository history;

    @BeforeEach
    void openDatabase(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        history = new PlayerHistoryRepository(database);
        history.createTables();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    @DisplayName("createTables can run again on an existing schema")
    void createTablesIsIdempotent() throws SQLException {
        history.createTables();
        history.createTables();
    }

    @Test
    @DisplayName("a first join records the account and its address")
    void firstJoin() throws SQLException {
        UUID id = UUID.randomUUID();
        history.recordJoin(id, "Kraxie", "10.0.0.1", NOW);

        assertEquals(new PlayerHistory(id, "Kraxie", NOW, NOW), history.find(id).orElseThrow());
        assertEquals(List.of(new PlayerIp(id, "10.0.0.1", NOW, NOW, 1)), history.ips(id));
        assertEquals("10.0.0.1", history.lastIp(id).orElseThrow());
    }

    @Test
    @DisplayName("a later join updates the name and counts the address again")
    void laterJoin() throws SQLException {
        UUID id = UUID.randomUUID();
        history.recordJoin(id, "Kraxie", "10.0.0.1", NOW);
        history.recordJoin(id, "Kraxie2", "10.0.0.1", NOW.plusSeconds(60));
        history.recordJoin(id, "Kraxie2", "10.0.0.2", NOW.plusSeconds(120));

        assertEquals(new PlayerHistory(id, "Kraxie2", NOW, NOW.plusSeconds(120)), history.find(id).orElseThrow());
        List<PlayerIp> ips = history.ips(id);
        assertEquals(List.of(
                new PlayerIp(id, "10.0.0.2", NOW.plusSeconds(120), NOW.plusSeconds(120), 1),
                new PlayerIp(id, "10.0.0.1", NOW, NOW.plusSeconds(60), 2)), ips);
        assertEquals("10.0.0.2", history.lastIp(id).orElseThrow());
    }

    @Test
    @DisplayName("a join without a known address still records the account")
    void unknownAddress() throws SQLException {
        UUID id = UUID.randomUUID();
        history.recordJoin(id, "Kraxie", null, NOW);

        assertTrue(history.find(id).isPresent());
        assertTrue(history.lastIp(id).isEmpty());
    }

    @Test
    @DisplayName("names are looked up regardless of case, most recently seen first")
    void findByName() throws SQLException {
        UUID offline = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        history.recordJoin(offline, "Kraxie", null, NOW);
        history.recordJoin(premium, "kraxie", null, NOW.plusSeconds(60));

        assertEquals(premium, history.findLatestByName("KRAXIE").orElseThrow().uuid());
        assertEquals(List.of(premium, offline),
                history.findByName("Kraxie").stream().map(PlayerHistory::uuid).toList());
        assertTrue(history.findLatestByName("Nobody").isEmpty());
    }

    @Test
    @DisplayName("accounts on an address are found within the window")
    void accountsOnAddress() throws SQLException {
        UUID recent = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();
        history.recordJoin(old, "Old", "10.0.0.9", NOW.minusSeconds(3600));
        history.recordJoin(recent, "Recent", "10.0.0.9", NOW);
        history.recordJoin(elsewhere, "Elsewhere", "10.0.0.8", NOW);

        assertEquals(List.of(recent, old),
                history.accountsOn("10.0.0.9", NOW.minusSeconds(7200)).stream().map(PlayerIp::uuid).toList());
        assertEquals(List.of(recent),
                history.accountsOn("10.0.0.9", NOW.minusSeconds(60)).stream().map(PlayerIp::uuid).toList());
    }

    @Test
    @DisplayName("a name or address too wide for its column is refused before it reaches SQL")
    void overlongRefused() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> history.recordJoin(id, "x".repeat(33), null, NOW));
        assertThrows(IllegalArgumentException.class, () -> history.recordJoin(id, "Kraxie", "1".repeat(46), NOW));
    }
}
