package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarnEscalationTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final IssuerRef STAFF = new IssuerRef(UUID.randomUUID(), "Moderator", IssuerRef.Source.GAME);

    private DatabaseManager database;
    private PunishmentService service;
    private WarnEscalation escalation;
    private final List<Punishment> issued = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        ConfigManager config = TemplateRegistryTest.config(directory, """
                warns:
                  escalation:
                    - count: 2
                      type: mute
                      duration: 1h
                    - count: 3
                      template: hacking
                      scope: lobby
                    - count: 4
                      type: warn
                    - count: 0
                      type: ban
                    - count: 5
                      template: nothing
                templates:
                  hacking:
                    type: ban
                    reason: Cheating
                    ladder: [7d, perm]
                """);
        PunishmentsConfig punishments = config.config().punishments();
        database = DatabaseTestSupport.started(directory, config.config().database());
        PunishmentRepository repository = new PunishmentRepository(database);
        repository.createTable();
        service = new PunishmentService(LoggerFactory.getLogger("test"), repository, new ActivePunishments(),
                Hierarchy.NONE, ip -> false, Clock.fixed(NOW, ZoneOffset.UTC));
        TemplateRegistry templates = TemplateRegistry.load(punishments.templates(), warnings::add);
        escalation = new WarnEscalation(LoggerFactory.getLogger("test"), service, templates,
                WarnEscalation.load(punishments.warns().escalation(), templates, warnings::add),
                new ScopeResolver(Map.of()), () -> List.of("lobby"));
        service.addObserver(new PunishmentObserver() {
            @Override
            public void issued(Punishment punishment) {
                issued.add(punishment);
            }
        });
        service.addObserver(escalation);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private Punishment warn(Target target) {
        return assertInstanceOf(IssueResult.Issued.class, service.issue(new PunishmentRequest(PunishmentType.WARN,
                target, IpMode.ACCOUNT, PunishmentScope.server("survival"), PunishmentLength.of(Duration.ofDays(30)),
                "rude", null, STAFF, false, false, null))).punishment();
    }

    private List<Punishment> escalations() {
        return issued.stream().filter(p -> p.issuer().source() == IssuerRef.Source.ESCALATION).toList();
    }

    @Test
    @DisplayName("steps that cannot work are reported and skipped")
    void loadSkipsBrokenSteps() {
        assertEquals(List.of(2, 3), escalation.steps().stream().map(WarnEscalation.Step::count).toList());
        assertEquals(3, warnings.size(), String.join("\n", warnings));
    }

    @Test
    @DisplayName("each step fires once, when the count lands on it")
    void firesOnExactCount() {
        Target target = new Target(UUID.randomUUID(), "Kraxie", "10.0.0.1");

        warn(target);
        assertTrue(escalations().isEmpty());

        Punishment second = warn(target);
        Punishment mute = escalations().get(0);
        assertEquals(PunishmentType.MUTE, mute.type());
        assertEquals(NOW.plus(Duration.ofHours(1)), mute.expiresAt());
        assertEquals(PunishmentScope.server("survival"), mute.scope(), "the warning's own scope");
        assertEquals(second.id(), mute.parentId());
        assertEquals(WarnEscalation.ISSUER_NAME, mute.issuer().name());
        assertEquals(null, mute.targetIp(), "never the address");
        assertEquals("Reached 2 warnings", mute.reason());

        warn(target);
        Punishment ban = escalations().get(1);
        assertEquals(PunishmentType.BAN, ban.type());
        assertEquals("hacking", ban.template());
        assertEquals(PunishmentScope.server("lobby"), ban.scope(), "the step's scope");
        assertEquals(NOW.plus(Duration.ofDays(7)), ban.expiresAt(), "the template's first step");

        warn(target);
        assertEquals(2, escalations().size(), "no step at four");
    }

    @Test
    @DisplayName("lifted warnings stop counting")
    void liftedWarningsDoNotCount() {
        Target target = new Target(UUID.randomUUID(), "Kraxie", null);
        Punishment first = warn(target);
        service.revoke(RevokeRequest.byId(PunishmentType.WARN, first.id(), STAFF, null));

        warn(target);
        assertTrue(escalations().isEmpty());
    }

    @Test
    @DisplayName("the next step is known, with its template's ladder applied for the player")
    void nextStep() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertEquals(2, escalation.next(0).orElseThrow().count());
        assertEquals(3, escalation.next(2).orElseThrow().count());
        assertTrue(escalation.next(3).isEmpty());

        service.issue(new PunishmentRequest(PunishmentType.BAN, new Target(uuid, "K", null), IpMode.ACCOUNT,
                PunishmentScope.global(), PunishmentLength.of(Duration.ofDays(7)), "Cheating", "hacking", STAFF,
                false, false, null));
        WarnEscalation.Outcome outcome = escalation.outcome(escalation.next(2).orElseThrow(), uuid).orElseThrow();
        assertTrue(outcome.length().isPermanent(), "a second offence");
    }

    @Test
    @DisplayName("/unwarn by player lifts only the newest warning")
    void newestOnly() throws Exception {
        Target target = new Target(UUID.randomUUID(), "Kraxie", null);
        Punishment older = warn(target);
        Punishment newer = warn(target);

        RevokeResult.Revoked revoked = assertInstanceOf(RevokeResult.Revoked.class,
                service.revoke(RevokeRequest.newest(PunishmentType.WARN, target, STAFF, null)));

        assertEquals(List.of(newer.id()), revoked.punishments().stream().map(Punishment::id).toList());
        assertEquals(List.of(older.id()), service.activeWarns(target.uuid()).stream().map(Punishment::id).toList());
    }
}
