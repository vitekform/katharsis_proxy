package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.DatabaseTestSupport;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final IssuerRef STAFF = new IssuerRef(UUID.randomUUID(), "Moderator", IssuerRef.Source.GAME);
    private static final String TRUSTED = "10.9.9.9";

    private DatabaseManager database;
    private PunishmentService service;
    private final List<Punishment> issued = new ArrayList<>();
    private final List<Punishment> revoked = new ArrayList<>();
    private Hierarchy.Verdict verdict = Hierarchy.Verdict.ALLOWED;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        PunishmentRepository repository = new PunishmentRepository(database);
        repository.createTable();
        service = new PunishmentService(LoggerFactory.getLogger("test"), repository, new ActivePunishments(),
                (issuer, target) -> verdict, Set.of(TRUSTED)::contains, Clock.fixed(NOW, ZoneOffset.UTC));
        service.addObserver(new PunishmentObserver() {
            @Override
            public void issued(Punishment punishment) {
                issued.add(punishment);
            }

            @Override
            public void revoked(Punishment punishment) {
                revoked.add(punishment);
            }
        });
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static PunishmentRequest ban(Target target, IpMode mode, PunishmentScope scope, boolean replace) {
        return new PunishmentRequest(PunishmentType.BAN, target, mode, scope, PunishmentLength.PERMANENT,
                "cheating", null, STAFF, false, replace, null);
    }

    private static Target player(String ip) {
        return new Target(UUID.randomUUID(), "Kraxie", ip);
    }

    private Punishment issuedOk(PunishmentRequest request) {
        return assertInstanceOf(IssueResult.Issued.class, service.issue(request)).punishment();
    }

    @Test
    @DisplayName("a default ban holds the account and its last address, and tells the observers")
    void banHoldsAccountAndAddress() throws Exception {
        Target target = player("10.0.0.1");
        long before = service.generation();

        Punishment ban = issuedOk(ban(target, IpMode.ACCOUNT_AND_IP, PunishmentScope.global(), false));

        assertEquals(target.uuid(), ban.targetUuid());
        assertEquals("10.0.0.1", ban.targetIp());
        assertEquals(List.of(ban), issued);
        assertNotEquals(before, service.generation());
        assertEquals(List.of(ban), service.activeAgainst(UUID.randomUUID(), "10.0.0.1"),
                "another account on the address is held too");
    }

    @Test
    @DisplayName("a temporary punishment ends its length after now")
    void temporary() {
        Punishment mute = issuedOk(new PunishmentRequest(PunishmentType.MUTE, player(null), IpMode.ACCOUNT,
                PunishmentScope.server("lobby"), PunishmentLength.of(Duration.ofHours(2)), "spam", null, STAFF,
                false, false, null));
        assertEquals(NOW, mute.createdAt());
        assertEquals(NOW.plus(Duration.ofHours(2)), mute.expiresAt());
    }

    @Test
    @DisplayName("without a usable address the account alone is banned, and the issuer is told")
    void addressSkipped() {
        for (String ip : new String[]{null, TRUSTED}) {
            IssueResult.Issued result = assertInstanceOf(IssueResult.Issued.class,
                    service.issue(ban(player(ip), IpMode.ACCOUNT_AND_IP, PunishmentScope.global(), false)));
            assertNull(result.punishment().targetIp());
            assertTrue(result.addressSkipped());
        }
    }

    @Test
    @DisplayName("an address-only ban needs a usable address")
    void addressOnly() {
        Punishment ban = issuedOk(ban(player("10.0.0.2"), IpMode.IP_ONLY, PunishmentScope.global(), false));
        assertNull(ban.targetUuid());
        assertEquals("10.0.0.2", ban.targetIp());

        assertInstanceOf(IssueResult.NoAddress.class,
                service.issue(ban(player(null), IpMode.IP_ONLY, PunishmentScope.global(), false)));
        assertInstanceOf(IssueResult.NoAddress.class,
                service.issue(ban(Target.address(TRUSTED), IpMode.IP_ONLY, PunishmentScope.global(), false)));
    }

    @Test
    @DisplayName("the same ban twice on the same scope is refused, unless replacing")
    void duplicates() {
        Target target = player(null);
        Punishment first = issuedOk(ban(target, IpMode.ACCOUNT, PunishmentScope.server("lobby"), false));

        IssueResult.AlreadyActive already = assertInstanceOf(IssueResult.AlreadyActive.class,
                service.issue(ban(target, IpMode.ACCOUNT, PunishmentScope.server("lobby"), false)));
        assertEquals(first.id(), already.existing().id());

        issuedOk(ban(target, IpMode.ACCOUNT, PunishmentScope.server("lifesteal"), false));

        IssueResult.Issued replacing = assertInstanceOf(IssueResult.Issued.class,
                service.issue(ban(target, IpMode.ACCOUNT, PunishmentScope.server("lobby"), true)));
        assertEquals(1, replacing.replaced());
        assertEquals(List.of(first.id()), revoked.stream().map(Punishment::id).toList());
        assertEquals(PunishmentService.REPLACED_REASON, revoked.get(0).revocation().reason());
    }

    @Test
    @DisplayName("warnings pile up and never hold an address")
    void warningsPileUp() {
        Target target = player("10.0.0.3");
        for (int i = 0; i < 2; i++) {
            Punishment warn = issuedOk(new PunishmentRequest(PunishmentType.WARN, target, IpMode.ACCOUNT_AND_IP,
                    PunishmentScope.global(), PunishmentLength.PERMANENT, "rude", null, STAFF, false, false, null));
            assertNull(warn.targetIp());
        }
    }

    @Test
    @DisplayName("a kick is stored but never active")
    void kick() throws Exception {
        Target target = player(null);
        Punishment kick = issuedOk(new PunishmentRequest(PunishmentType.KICK, target, IpMode.ACCOUNT,
                PunishmentScope.global(), PunishmentLength.of(Duration.ofDays(1)), "afk", null, STAFF, false, false,
                null));
        assertNull(kick.expiresAt(), "a kick ignores the length");
        assertTrue(service.activeAgainst(target.uuid(), null).isEmpty());
        assertEquals(1, service.countHistory(target));
    }

    @Test
    @DisplayName("staff cannot punish the exempt or those who outrank them; automatic issuers can")
    void hierarchy() {
        verdict = Hierarchy.Verdict.EXEMPT;
        assertInstanceOf(IssueResult.Exempt.class,
                service.issue(ban(player(null), IpMode.ACCOUNT, PunishmentScope.global(), false)));
        verdict = Hierarchy.Verdict.OUTRANKED;
        assertInstanceOf(IssueResult.Outranked.class,
                service.issue(ban(player(null), IpMode.ACCOUNT, PunishmentScope.global(), false)));
        assertInstanceOf(IssueResult.Issued.class, service.issue(new PunishmentRequest(PunishmentType.WARN,
                player(null), IpMode.ACCOUNT, PunishmentScope.global(), PunishmentLength.PERMANENT, "flagged", null,
                new IssuerRef(null, "AutoMod", IssuerRef.Source.AUTOMOD), false, false, null)));
        assertTrue(issued.stream().noneMatch(p -> p.type() == PunishmentType.BAN));
    }

    @Test
    @DisplayName("lifting by target takes every active ban, or only those on one scope")
    void revokeByTarget() throws Exception {
        Target target = player(null);
        Punishment lobby = issuedOk(ban(target, IpMode.ACCOUNT, PunishmentScope.server("lobby"), false));
        Punishment global = issuedOk(ban(target, IpMode.ACCOUNT, PunishmentScope.global(), false));

        RevokeResult.Revoked scoped = assertInstanceOf(RevokeResult.Revoked.class, service.revoke(
                RevokeRequest.byTarget(PunishmentType.BAN, target, PunishmentScope.server("lobby"), STAFF, "ok")));
        assertEquals(List.of(lobby.id()), scoped.punishments().stream().map(Punishment::id).toList());
        assertEquals("ok", scoped.punishments().get(0).revocation().reason());

        assertInstanceOf(RevokeResult.Revoked.class,
                service.revoke(RevokeRequest.byTarget(PunishmentType.BAN, target, null, STAFF, null)));
        assertTrue(service.activeAgainst(target.uuid(), null).isEmpty());
        assertEquals(List.of(lobby.id(), global.id()), revoked.stream().map(Punishment::id).toList());

        assertInstanceOf(RevokeResult.NothingActive.class,
                service.revoke(RevokeRequest.byTarget(PunishmentType.BAN, target, null, STAFF, null)));
    }

    @Test
    @DisplayName("lifting by id checks the type and that it is still active")
    void revokeById() {
        Punishment ban = issuedOk(ban(player(null), IpMode.ACCOUNT, PunishmentScope.global(), false));

        assertInstanceOf(RevokeResult.NothingActive.class,
                service.revoke(RevokeRequest.byId(PunishmentType.MUTE, ban.id(), STAFF, null)));
        assertInstanceOf(RevokeResult.Revoked.class,
                service.revoke(RevokeRequest.byId(PunishmentType.BAN, ban.id(), STAFF, null)));
        assertInstanceOf(RevokeResult.NothingActive.class,
                service.revoke(RevokeRequest.byId(PunishmentType.BAN, ban.id(), STAFF, null)));
    }

    @Test
    @DisplayName("an address is lifted by naming the address, not an account that shares it")
    void revokeAddress() throws Exception {
        Punishment ban = issuedOk(ban(Target.address("10.0.0.4"), IpMode.IP_ONLY, PunishmentScope.global(), false));
        assertInstanceOf(RevokeResult.NothingActive.class, service.revoke(RevokeRequest.byTarget(
                PunishmentType.BAN, new Target(UUID.randomUUID(), "Other", "10.0.0.4"), null, STAFF, null)));
        assertInstanceOf(RevokeResult.Revoked.class, service.revoke(RevokeRequest.byTarget(
                PunishmentType.BAN, Target.address("10.0.0.4"), null, STAFF, null)));
        assertTrue(service.activeAgainst(null, ban.targetIp()).isEmpty());
    }

    @Test
    @DisplayName("sessions online are kept in step with what is issued and lifted")
    void sessionsFollow() {
        Target target = player("10.0.0.5");
        service.active().put(target.uuid(), new ActivePunishments.Session(new Object(), "10.0.0.5", List.of(), 0));

        Punishment mute = issuedOk(new PunishmentRequest(PunishmentType.MUTE, target, IpMode.ACCOUNT,
                PunishmentScope.global(), PunishmentLength.PERMANENT, "spam", null, STAFF, false, false, null));
        assertEquals(List.of(mute), service.active().active(target.uuid(), PunishmentType.MUTE, NOW));

        service.revoke(RevokeRequest.byId(PunishmentType.MUTE, mute.id(), STAFF, null));
        assertTrue(service.active().active(target.uuid(), PunishmentType.MUTE, NOW).isEmpty());
    }

    @Test
    @DisplayName("the network-wide ban that lasts longest is the one shown")
    void globalBanPicksLongest() {
        UUID uuid = UUID.randomUUID();
        Punishment shortBan = new Punishment(1, PunishmentType.BAN, uuid, "K", null, PunishmentScope.global(),
                "a", null, STAFF, false, null, NOW, NOW.plusSeconds(60), null);
        Punishment permanent = new Punishment(2, PunishmentType.BAN, null, "10.0.0.6", "10.0.0.6",
                PunishmentScope.global(), "b", null, STAFF, false, null, NOW, null, null);
        Punishment scoped = new Punishment(3, PunishmentType.BAN, uuid, "K", null, PunishmentScope.server("lobby"),
                "c", null, STAFF, false, null, NOW, null, null);
        List<Punishment> all = List.of(shortBan, permanent, scoped);

        assertEquals(permanent, PunishmentService.globalBan(all, uuid, false).orElseThrow());
        assertEquals(shortBan, PunishmentService.globalBan(all, uuid, true).orElseThrow(),
                "an exempt player is held only by bans on their own account");
    }
}
