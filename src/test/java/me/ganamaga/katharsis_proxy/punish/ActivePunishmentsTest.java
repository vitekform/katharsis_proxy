package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivePunishmentsTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final ActivePunishments active = new ActivePunishments();
    private final Object connection = new Object();

    private static Punishment mute(long id, UUID uuid, String ip, Instant expiresAt) {
        return new Punishment(id, PunishmentType.MUTE, uuid, "Target", ip, PunishmentScope.global(), "spam",
                null, IssuerRef.console(), false, null, NOW.minusSeconds(60), expiresAt, null);
    }

    @Test
    @DisplayName("a new punishment reaches its account and everyone on its address")
    void addedReachesAccountAndAddress() {
        UUID target = UUID.randomUUID();
        UUID sameAddress = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();
        active.put(target, new ActivePunishments.Session(connection, "10.0.0.2", List.of(), 0));
        active.put(sameAddress, new ActivePunishments.Session(connection, "10.0.0.1", List.of(), 0));
        active.put(elsewhere, new ActivePunishments.Session(connection, "10.0.0.3", List.of(), 0));

        Punishment mute = mute(1, target, "10.0.0.1", null);
        List<UUID> affected = active.added(mute);

        assertEquals(2, affected.size());
        assertEquals(List.of(mute), active.active(target, PunishmentType.MUTE, NOW));
        assertEquals(List.of(mute), active.active(sameAddress, PunishmentType.MUTE, NOW));
        assertTrue(active.active(elsewhere, PunishmentType.MUTE, NOW).isEmpty());
        assertTrue(active.active(target, PunishmentType.BAN, NOW).isEmpty(), "only the type asked for");
    }

    @Test
    @DisplayName("a lifted punishment is dropped everywhere")
    void revoked() {
        UUID target = UUID.randomUUID();
        Punishment kept = mute(1, target, null, null);
        Punishment lifted = mute(2, target, null, null);
        active.put(target, new ActivePunishments.Session(connection, null, List.of(kept, lifted), 0));

        active.revoked(2);

        assertEquals(List.of(kept), active.active(target, PunishmentType.MUTE, NOW));
    }

    @Test
    @DisplayName("expired punishments are reported once and dropped")
    void evictExpired() {
        UUID target = UUID.randomUUID();
        Punishment expired = mute(1, target, null, NOW);
        Punishment running = mute(2, target, null, NOW.plusSeconds(60));
        active.put(target, new ActivePunishments.Session(connection, null, List.of(expired, running), 0));

        assertEquals(Map.of(target, List.of(expired)), active.evictExpired(NOW));
        assertEquals(Map.of(), active.evictExpired(NOW));
        assertEquals(List.of(running), active.active(target, PunishmentType.MUTE, NOW));
    }

    @Test
    @DisplayName("only the connection that loaded a session can drop it")
    void removeChecksOwner() {
        UUID target = UUID.randomUUID();
        active.put(target, new ActivePunishments.Session(connection, null, List.of(), 0));

        active.remove(target, new Object());
        assertTrue(active.session(target).isPresent(), "a duplicate login leaving does not drop it");
        active.remove(target, connection);
        assertTrue(active.session(target).isEmpty());
    }

    @Test
    @DisplayName("kicks never enter a session")
    void kicksIgnored() {
        UUID target = UUID.randomUUID();
        active.put(target, new ActivePunishments.Session(connection, null, List.of(), 0));
        Punishment kick = new Punishment(1, PunishmentType.KICK, target, "Target", null, PunishmentScope.global(),
                "x", null, IssuerRef.console(), false, null, NOW, null, null);
        assertTrue(active.added(kick).isEmpty());
    }

    @Test
    @DisplayName("an exempt player is not held by a new ban on an address they share")
    void exemptIgnoresAddress() {
        UUID staff = UUID.randomUUID();
        active.put(staff, new ActivePunishments.Session(connection, "10.0.0.1", List.of(), 0, true));

        assertTrue(active.added(mute(1, UUID.randomUUID(), "10.0.0.1", null)).isEmpty());
        Punishment own = mute(2, staff, "10.0.0.1", null);
        assertEquals(List.of(staff), active.added(own), "their own account still counts");
    }

    @Test
    @DisplayName("banOn finds bans covering the server, directly or through a group")
    void banOn() {
        ScopeResolver scopes = new ScopeResolver(Map.of("survival", List.of("lifesteal", "lifesteal2")));
        UUID target = UUID.randomUUID();
        Punishment group = new Punishment(1, PunishmentType.BAN, target, "Target", null,
                PunishmentScope.group("survival"), "x", null, IssuerRef.console(), false, null,
                NOW.minusSeconds(60), NOW.plusSeconds(60), null);
        Punishment server = new Punishment(2, PunishmentType.BAN, target, "Target", null,
                PunishmentScope.server("lifesteal"), "x", null, IssuerRef.console(), false, null,
                NOW.minusSeconds(60), null, null);
        active.put(target, new ActivePunishments.Session(connection, null, List.of(group, server), 0));

        assertEquals(server, active.banOn(target, "LifeSteal", scopes, NOW).orElseThrow(), "the longest wins");
        assertEquals(group, active.banOn(target, "lifesteal2", scopes, NOW).orElseThrow());
        assertTrue(active.banOn(target, "lobby", scopes, NOW).isEmpty());
        assertTrue(active.banOn(UUID.randomUUID(), "lifesteal", scopes, NOW).isEmpty());
    }
}
