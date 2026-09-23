package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.database.AuthAccountRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.DatabaseTestSupport;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.entity.AuthAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private DatabaseManager database;
    private PlayerHistoryRepository history;
    private AuthAccountRepository accounts;
    private final Target online = new Target(UUID.randomUUID(), "Online", "10.0.0.9");

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        history = new PlayerHistoryRepository(database);
        history.createTables();
        accounts = new AuthAccountRepository(database);
        accounts.createTable();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private TargetResolver resolver(boolean offlineMode) {
        return new TargetResolver(name -> name.equalsIgnoreCase("online") ? Optional.of(online) : Optional.empty(),
                history, accounts, offlineMode);
    }

    @Test
    @DisplayName("an address literal is an address, even if a player were called that")
    void address() throws Exception {
        assertEquals(Target.address("10.1.2.3"), resolver(false).resolve("10.1.2.3", false).orElseThrow());
    }

    @Test
    @DisplayName("a player online comes first")
    void online() throws Exception {
        assertEquals(online, resolver(false).resolve("ONLINE", false).orElseThrow());
    }

    @Test
    @DisplayName("a player seen before is found with their last address")
    void seenBefore() throws Exception {
        UUID uuid = UUID.randomUUID();
        history.recordJoin(uuid, "Kraxie", "10.0.0.1", NOW);
        history.recordJoin(uuid, "Kraxie", "10.0.0.2", NOW.plusSeconds(60));

        assertEquals(new Target(uuid, "Kraxie", "10.0.0.2"), resolver(false).resolve("kraxie", false).orElseThrow());
    }

    @Test
    @DisplayName("an account that never reached a server is found by its registration")
    void registeredOnly() throws Exception {
        UUID uuid = UUID.randomUUID();
        accounts.insert(AuthAccount.registered("Quiet", uuid, "$argon2id$hash", NOW));

        assertEquals(new Target(uuid, "Quiet", null), resolver(false).resolve("quiet", false).orElseThrow());
    }

    @Test
    @DisplayName("a name nobody used is only accepted when forced, and only on an offline-mode proxy")
    void unknown() throws Exception {
        assertTrue(resolver(true).resolve("Stranger", false).isEmpty());
        assertTrue(resolver(false).resolve("Stranger", true).isEmpty());
        assertEquals(new Target(TargetResolver.offlineUuid("Stranger"), "Stranger", null),
                resolver(true).resolve("Stranger", true).orElseThrow());
        assertTrue(resolver(true).resolve("not a name!", true).isEmpty());
    }
}
