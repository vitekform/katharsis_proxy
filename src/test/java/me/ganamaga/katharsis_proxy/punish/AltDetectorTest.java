package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.config.AltsConfig;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.DatabaseTestSupport;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AltDetector} and {@link JoinInspector}, which acts on what it finds. */
class AltDetectorTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final IssuerRef STAFF = new IssuerRef(UUID.randomUUID(), "Moderator", IssuerRef.Source.GAME);
    private static final String TRUSTED = "10.9.9.9";

    private DatabaseManager database;
    private PlayerHistoryRepository history;
    private PunishmentService service;
    private AltsConfig alts;
    private AltDetector detector;
    private JoinInspector inspector;
    private final List<Punishment> issued = new ArrayList<>();
    private final List<Component> staffChat = new ArrayList<>();

    private void setUp(Path directory, String yaml) throws Exception {
        ConfigManager config = TemplateRegistryTest.config(directory, yaml);
        alts = config.config().punishments().alts();
        database = DatabaseTestSupport.started(directory, config.config().database());
        history = new PlayerHistoryRepository(database);
        history.createTables();
        PunishmentRepository punishments = new PunishmentRepository(database);
        punishments.createTable();
        service = new PunishmentService(LoggerFactory.getLogger("test"), punishments, new ActivePunishments(),
                Hierarchy.NONE, Set.of(TRUSTED)::contains, Clock.fixed(NOW, ZoneOffset.UTC));
        service.addObserver(new PunishmentObserver() {
            @Override
            public void issued(Punishment punishment) {
                issued.add(punishment);
            }
        });
        detector = new AltDetector(history, service, () -> alts);

        LanguageRegistry registry = new LanguageRegistry(LoggerFactory.getLogger("test"), directory);
        registry.load("en_US");
        PunishmentText text = new PunishmentText(new Messages(registry, new PlayerLanguageCache()), () -> "");
        Player staff = player(UUID.randomUUID(), "Staff", false);
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (p, method, args) ->
                        method.getName().equals("getAllPlayers") ? List.of(staff) : null);
        inspector = new JoinInspector(LoggerFactory.getLogger("test"), service, detector,
                new StaffNotifier(proxy, text), () -> alts);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    /** A player; the one called Staff holds notify and records what staff are told. */
    private Player player(UUID uuid, String name, boolean exempt) {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "hasPermission" -> name.equals("Staff") ? PunishPermissions.NOTIFY.equals(args[0])
                            : exempt && PunishPermissions.EXEMPT.equals(args[0]);
                    case "sendMessage" -> {
                        staffChat.add((Component) args[0]);
                        yield null;
                    }
                    default -> null;
                });
    }

    private Punishment ban(UUID uuid, PunishmentScope scope, Instant expiresAt) {
        return assertInstanceOf(IssueResult.Issued.class, service.issue(new PunishmentRequest(PunishmentType.BAN,
                new Target(uuid, "Banned", null), IpMode.ACCOUNT, scope,
                expiresAt == null ? PunishmentLength.PERMANENT : PunishmentLength.of(Duration.between(NOW, expiresAt)),
                "Cheating", null, STAFF, false, false, null))).punishment();
    }

    private String staffSaid() {
        return String.join("\n", staffChat.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .toList());
    }

    @Test
    @DisplayName("alts are grouped per account, with their state; trusted addresses do not link anyone")
    void alts(@TempDir Path directory) throws Exception {
        setUp(directory, "alts:\n  lookback: 1d\n");
        UUID main = UUID.randomUUID();
        UUID banned = UUID.randomUUID();
        UUID muted = UUID.randomUUID();
        UUID proxied = UUID.randomUUID();
        history.recordJoin(main, "Main", "10.0.0.1", NOW);
        history.recordJoin(main, "Main", "10.0.0.2", NOW);
        history.recordJoin(main, "Main", TRUSTED, NOW);
        history.recordJoin(banned, "Banned", "10.0.0.1", NOW.minusSeconds(10));
        history.recordJoin(banned, "Banned", "10.0.0.2", NOW.minusSeconds(20));
        history.recordJoin(muted, "Muted", "10.0.0.2", NOW.minusSeconds(30));
        history.recordJoin(proxied, "Proxied", TRUSTED, NOW);
        Punishment ban = ban(banned, PunishmentScope.global(), null);
        service.issue(new PunishmentRequest(PunishmentType.MUTE, new Target(muted, "Muted", null), IpMode.ACCOUNT,
                PunishmentScope.server("lobby"), PunishmentLength.PERMANENT, "spam", null, STAFF, false, false, null));

        List<AltDetector.Alt> found = detector.alts(main);

        assertEquals(List.of("Banned", "Muted"), found.stream().map(AltDetector.Alt::name).toList());
        AltDetector.Alt first = found.get(0);
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), first.sharedIps());
        assertEquals(ban.id(), first.globalBan().orElseThrow().id());
        assertTrue(first.banned());
        assertTrue(found.get(1).muted());
        assertFalse(found.get(1).banned());
        assertTrue(found.get(1).globalBan().isEmpty());
    }

    @Test
    @DisplayName("joining from a banned account's address alerts staff, and bans too if configured")
    void evasion(@TempDir Path directory) throws Exception {
        setUp(directory, "alts:\n  evasion: ban\n  join-alerts: false\n");
        UUID banned = UUID.randomUUID();
        UUID evader = UUID.randomUUID();
        history.recordJoin(banned, "Banned", "10.0.0.1", NOW.minusSeconds(60));
        Punishment original = ban(banned, PunishmentScope.global(), NOW.plus(Duration.ofDays(2)));
        history.recordJoin(evader, "Evader", "10.0.0.1", NOW);
        issued.clear();

        inspector.inspect(player(evader, "Evader", false), "10.0.0.1");

        assertTrue(staffSaid().contains("Evader joined from an address of Banned"), staffSaid());
        Punishment evasion = issued.get(0);
        assertEquals(evader, evasion.targetUuid());
        assertTrue(evasion.scope().isGlobal());
        assertEquals(original.expiresAt(), evasion.expiresAt(), "as long as the original has left");
        assertEquals(original.id(), evasion.parentId());
        assertEquals(IssuerRef.Source.EVASION, evasion.issuer().source());
    }

    @Test
    @DisplayName("a server ban on the alt is no evasion; exempt players are not looked at")
    void noEvasion(@TempDir Path directory) throws Exception {
        setUp(directory, "alts:\n  evasion: ban\n  join-alerts: false\n");
        UUID banned = UUID.randomUUID();
        history.recordJoin(banned, "Banned", "10.0.0.1", NOW);
        ban(banned, PunishmentScope.server("lobby"), null);
        UUID other = UUID.randomUUID();
        history.recordJoin(other, "Other", "10.0.0.1", NOW);
        issued.clear();

        inspector.inspect(player(other, "Other", false), "10.0.0.1");
        assertTrue(issued.isEmpty());
        assertTrue(staffChat.isEmpty());

        ban(banned, PunishmentScope.global(), null);
        issued.clear();
        inspector.inspect(player(other, "Other", true), "10.0.0.1");
        assertTrue(issued.isEmpty(), "exempt");
    }

    @Test
    @DisplayName("staff hear of a player joining with warnings or banned alts")
    void joinAlerts(@TempDir Path directory) throws Exception {
        setUp(directory, "alts:\n  evasion: ignore\n  join-alert-min-warns: 2\n");
        UUID player = UUID.randomUUID();
        history.recordJoin(player, "Kraxie", "10.0.0.1", NOW);
        Target target = new Target(player, "Kraxie", null);
        service.issue(new PunishmentRequest(PunishmentType.WARN, target, IpMode.ACCOUNT, PunishmentScope.global(),
                PunishmentLength.PERMANENT, "rude", null, STAFF, false, false, null));

        inspector.inspect(player(player, "Kraxie", false), "10.0.0.1");
        assertTrue(staffChat.isEmpty(), "one warning is under the threshold");

        UUID banned = UUID.randomUUID();
        history.recordJoin(banned, "Banned", "10.0.0.1", NOW);
        ban(banned, PunishmentScope.server("lobby"), null);
        inspector.inspect(player(player, "Kraxie", false), "10.0.0.1");
        assertTrue(staffSaid().contains("Kraxie joined with 1 active warning(s) and 1 banned alt(s)."), staffSaid());
        assertTrue(issued.stream().noneMatch(p -> p.issuer().source() == IssuerRef.Source.EVASION));
    }
}
