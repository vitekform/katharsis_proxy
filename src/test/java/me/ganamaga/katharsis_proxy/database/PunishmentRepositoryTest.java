package me.ganamaga.katharsis_proxy.database;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final IssuerRef STAFF = new IssuerRef(UUID.randomUUID(), "Moderator", IssuerRef.Source.GAME);

    private DatabaseManager database;
    private PunishmentRepository punishments;

    @BeforeEach
    void openDatabase(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        punishments = new PunishmentRepository(database);
        punishments.createTable();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private static Punishment punishment(PunishmentType type, UUID uuid, String ip, PunishmentScope scope,
                                         Instant createdAt, Instant expiresAt) {
        return new Punishment(0, type, uuid, uuid == null ? ip : "Target", ip, scope, "because", null, STAFF,
                false, null, createdAt, expiresAt, null);
    }

    private static Punishment ban(UUID uuid, String ip) {
        return punishment(PunishmentType.BAN, uuid, ip, PunishmentScope.global(), NOW.minusSeconds(60), null);
    }

    @Test
    @DisplayName("createTable can run again on an existing schema")
    void createTableIsIdempotent() throws SQLException {
        punishments.createTable();
        punishments.createTable();
    }

    @Test
    @DisplayName("a stored punishment gets an id and round-trips every field")
    void roundTrip() throws SQLException {
        UUID target = UUID.randomUUID();
        Punishment original = new Punishment(0, PunishmentType.MUTE, target, "Target", "10.0.0.1",
                PunishmentScope.group("survival"), "spam", "spam", STAFF, true, 7L,
                NOW, NOW.plus(Duration.ofHours(1)), null);

        Punishment stored = punishments.insert(original);

        assertNotEquals(0, stored.id());
        assertEquals(stored, punishments.find(stored.id()).orElseThrow());
    }

    @Test
    @DisplayName("ids keep counting up")
    void idsIncrease() throws SQLException {
        long first = punishments.insert(ban(UUID.randomUUID(), null)).id();
        long second = punishments.insert(ban(UUID.randomUUID(), null)).id();
        assertTrue(second > first);
    }

    @Test
    @DisplayName("console and plugin issuers, without a uuid, are stored")
    void issuerWithoutUuid() throws SQLException {
        Punishment warn = new Punishment(0, PunishmentType.WARN, UUID.randomUUID(), "Target", null,
                PunishmentScope.global(), "flagged chat", null,
                new IssuerRef(null, "AutoMod", IssuerRef.Source.AUTOMOD), false, null, NOW, null, null);
        Punishment stored = punishments.insert(warn);
        assertEquals(warn.withId(stored.id()), punishments.find(stored.id()).orElseThrow());
    }

    @Test
    @DisplayName("an address-only ban is stored without an account")
    void ipOnly() throws SQLException {
        Punishment stored = punishments.insert(ban(null, "10.0.0.2"));
        Punishment found = punishments.find(stored.id()).orElseThrow();
        assertNull(found.targetUuid());
        assertEquals("10.0.0.2", found.targetIp());
    }

    @Test
    @DisplayName("an already stored or revoked punishment is refused")
    void insertRefusesStoredOrRevoked() throws SQLException {
        Punishment stored = punishments.insert(ban(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> punishments.insert(stored));
        Punishment revoked = ban(UUID.randomUUID(), null)
                .withRevocation(new Punishment.Revocation(NOW, STAFF, null));
        assertThrows(IllegalArgumentException.class, () -> punishments.insert(revoked));
    }

    @Test
    @DisplayName("active punishments match the account or the address")
    void activeMatchesAccountOrAddress() throws SQLException {
        UUID player = UUID.randomUUID();
        Punishment byAccount = punishments.insert(ban(player, null));
        Punishment byAddress = punishments.insert(ban(UUID.randomUUID(), "10.0.0.3"));
        punishments.insert(ban(UUID.randomUUID(), "10.0.0.4"));

        assertEquals(List.of(byAccount), punishments.findActive(player, null, NOW));
        assertEquals(List.of(byAddress), punishments.findActive(null, "10.0.0.3", NOW));
        List<Punishment> both = punishments.findActive(player, "10.0.0.3", NOW);
        assertEquals(2, both.size());
        assertTrue(both.containsAll(List.of(byAccount, byAddress)));
    }

    @Test
    @DisplayName("a punishment running out exactly now is no longer active")
    void expiryBoundary() throws SQLException {
        UUID player = UUID.randomUUID();
        punishments.insert(punishment(PunishmentType.BAN, player, null, PunishmentScope.global(),
                NOW.minusSeconds(60), NOW));
        Punishment later = punishments.insert(punishment(PunishmentType.MUTE, player, null,
                PunishmentScope.global(), NOW.minusSeconds(60), NOW.plusSeconds(1)));

        assertEquals(List.of(later), punishments.findActive(player, null, NOW));
    }

    @Test
    @DisplayName("kicks are history, never active")
    void kicksAreNeverActive() throws SQLException {
        UUID player = UUID.randomUUID();
        punishments.insert(punishment(PunishmentType.KICK, player, null, PunishmentScope.global(),
                NOW.minusSeconds(1), null));

        assertTrue(punishments.findActive(player, null, NOW).isEmpty());
        assertEquals(1, punishments.countHistory(player, null));
    }

    @Test
    @DisplayName("revoking lifts a punishment once and keeps it in the history")
    void revoke() throws SQLException {
        UUID player = UUID.randomUUID();
        Punishment stored = punishments.insert(ban(player, "10.0.0.5"));
        Punishment.Revocation revocation = new Punishment.Revocation(NOW, IssuerRef.console(), "appeal accepted");

        assertTrue(punishments.revoke(stored.id(), revocation));
        assertFalse(punishments.revoke(stored.id(), revocation), "already lifted");
        assertFalse(punishments.revoke(stored.id() + 100, revocation), "no such punishment");

        assertTrue(punishments.findActive(player, "10.0.0.5", NOW).isEmpty());
        assertEquals(stored.withRevocation(revocation), punishments.find(stored.id()).orElseThrow());
        assertEquals(1, punishments.countHistory(player, null));
    }

    @Test
    @DisplayName("history pages newest first")
    void historyPages() throws SQLException {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            punishments.insert(punishment(PunishmentType.WARN, player, null, PunishmentScope.server("lobby"),
                    NOW.plusSeconds(i), null));
        }
        punishments.insert(ban(UUID.randomUUID(), null));

        assertEquals(5, punishments.countHistory(player, null));
        List<Punishment> first = punishments.findHistory(player, null, 2, 0);
        List<Punishment> last = punishments.findHistory(player, null, 2, 4);
        assertEquals(NOW.plusSeconds(4), first.get(0).createdAt());
        assertEquals(NOW.plusSeconds(3), first.get(1).createdAt());
        assertEquals(1, last.size());
        assertEquals(NOW, last.get(0).createdAt());
    }

    @Test
    @DisplayName("the template count ignores lifted punishments and other templates")
    void countByTemplate() throws SQLException {
        UUID player = UUID.randomUUID();
        for (String template : new String[]{"hacking", "hacking", "hacking", "spam"}) {
            punishments.insert(new Punishment(0, PunishmentType.BAN, player, "Target", null,
                    PunishmentScope.global(), "Cheating", template, STAFF, false, null,
                    NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(30)), null));
        }
        long appealed = punishments.findHistory(player, null, 10, 0).stream()
                .filter(p -> "hacking".equals(p.template()))
                .findFirst().orElseThrow().id();
        punishments.revoke(appealed, new Punishment.Revocation(NOW, STAFF, null));

        assertEquals(2, punishments.countByTemplate(player, "hacking"),
                "expired ones count, the lifted one does not");
        assertEquals(0, punishments.countByTemplate(UUID.randomUUID(), "hacking"));
    }

    @Test
    @DisplayName("a query needs an account or an address")
    void queryNeedsATarget() {
        assertThrows(IllegalArgumentException.class, () -> punishments.findActive(null, null, NOW));
    }
}
