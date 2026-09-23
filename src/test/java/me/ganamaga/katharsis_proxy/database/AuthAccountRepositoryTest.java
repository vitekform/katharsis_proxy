package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.AuthAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthAccountRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private DatabaseManager database;
    private AuthAccountRepository accounts;

    @BeforeEach
    void openDatabase(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        accounts = new AuthAccountRepository(database);
        accounts.createTable();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    @DisplayName("createTable can run again on an existing schema")
    void createTableIsIdempotent() throws SQLException {
        accounts.createTable();
    }

    @Test
    @DisplayName("a registered account round-trips and is found in any case")
    void roundTrip() throws SQLException {
        UUID id = UUID.randomUUID();
        assertTrue(accounts.insert(AuthAccount.registered("Steve", id, "$argon2id$hash", NOW)));

        AuthAccount stored = accounts.find("sTEVE").orElseThrow();
        assertEquals("Steve", stored.username(), "the claimed spelling is kept");
        assertEquals(id, stored.playerUuid());
        assertEquals("$argon2id$hash", stored.passwordHash());
        assertFalse(stored.premium());
        assertEquals(NOW, stored.registeredAt());
        assertEquals(NOW, stored.lastLoginAt());
    }

    @Test
    @DisplayName("a name cannot be claimed twice, whatever the case")
    void uniqueIgnoringCase() throws SQLException {
        accounts.insert(AuthAccount.registered("Steve", UUID.randomUUID(), "$argon2id$one", NOW));

        assertFalse(accounts.insert(AuthAccount.registered("STEVE", UUID.randomUUID(), "$argon2id$two", NOW)));
        assertEquals("$argon2id$one", accounts.find("steve").orElseThrow().passwordHash());
    }

    @Test
    @DisplayName("an account that never logged in reads back with no last login")
    void nullLastLogin() throws SQLException {
        accounts.insert(new AuthAccount("Alex", UUID.randomUUID(), "$argon2id$hash", false, NOW, null));

        assertNull(accounts.find("Alex").orElseThrow().lastLoginAt());
    }

    @Test
    @DisplayName("logins are stamped and passwords replaced")
    void updates() throws SQLException {
        accounts.insert(AuthAccount.registered("Steve", UUID.randomUUID(), "$argon2id$old", NOW));
        Instant later = NOW.plus(1, ChronoUnit.DAYS);

        accounts.recordLogin("steve", later);
        assertTrue(accounts.updatePassword("STEVE", "$argon2id$new"));

        AuthAccount stored = accounts.find("Steve").orElseThrow();
        assertEquals(later, stored.lastLoginAt());
        assertEquals("$argon2id$new", stored.passwordHash());
        assertFalse(accounts.updatePassword("Nobody", "$argon2id$x"));
    }

    @Test
    @DisplayName("Mojang claims a free name, and its latest owner takes it over")
    void premiumClaims() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(accounts.savePremium("Notch", first, NOW));
        assertTrue(accounts.savePremium("notch", second, NOW.plusSeconds(60)));

        AuthAccount stored = accounts.find("NOTCH").orElseThrow();
        assertTrue(stored.premium());
        assertNull(stored.passwordHash());
        assertEquals(second, stored.playerUuid());
        assertEquals("notch", stored.username());
        assertEquals(NOW, stored.registeredAt(), "still the first claim");
    }

    @Test
    @DisplayName("Mojang never takes over a name registered with a password")
    void premiumLeavesPasswordAccounts() throws SQLException {
        UUID id = UUID.randomUUID();
        accounts.insert(AuthAccount.registered("Steve", id, "$argon2id$hash", NOW));

        assertFalse(accounts.savePremium("Steve", UUID.randomUUID(), NOW));
        assertFalse(accounts.updatePassword("Notch", "$argon2id$x"));

        AuthAccount stored = accounts.find("Steve").orElseThrow();
        assertFalse(stored.premium());
        assertEquals(id, stored.playerUuid());
    }

    @Test
    @DisplayName("a premium account's password cannot be set")
    void premiumHasNoPassword() throws SQLException {
        accounts.savePremium("Notch", UUID.randomUUID(), NOW);

        assertFalse(accounts.updatePassword("Notch", "$argon2id$x"));
        assertNull(accounts.find("Notch").orElseThrow().passwordHash());
    }
}
