package me.ganamaga.katharsis_proxy.punish.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.DatabaseTestSupport;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import me.ganamaga.katharsis_proxy.punish.ActivePunishments;
import me.ganamaga.katharsis_proxy.punish.Hierarchy;
import me.ganamaga.katharsis_proxy.punish.IpMode;
import me.ganamaga.katharsis_proxy.punish.PunishPermissions;
import me.ganamaga.katharsis_proxy.punish.PunishmentLength;
import me.ganamaga.katharsis_proxy.punish.PunishmentRequest;
import me.ganamaga.katharsis_proxy.punish.PunishmentService;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.Target;
import me.ganamaga.katharsis_proxy.punish.TargetResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentLoginListenerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final IssuerRef STAFF = new IssuerRef(UUID.randomUUID(), "Moderator", IssuerRef.Source.GAME);

    private DatabaseManager database;
    private PlayerHistoryRepository history;
    private PlayerRepository players;
    private PunishmentService service;
    private PunishmentLoginListener listener;
    private final AtomicBoolean failClosed = new AtomicBoolean();

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        database = DatabaseTestSupport.started(directory);
        history = new PlayerHistoryRepository(database);
        history.createTables();
        players = new PlayerRepository(database);
        players.createTable();
        PunishmentRepository punishments = new PunishmentRepository(database);
        punishments.createTable();
        service = new PunishmentService(LoggerFactory.getLogger("test"), punishments, new ActivePunishments(),
                Hierarchy.NONE, ip -> false, Clock.fixed(NOW, ZoneOffset.UTC));

        LanguageRegistry registry = new LanguageRegistry(LoggerFactory.getLogger("test"), directory);
        registry.load("en_US");
        PunishmentText text = new PunishmentText(new Messages(registry, new PlayerLanguageCache()),
                () -> "discord.gg/appeal");
        listener = new PunishmentLoginListener(LoggerFactory.getLogger("test"), service, history, players, text,
                failClosed::get);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    // --- stubs -------------------------------------------------------------

    /** A player stub; {@code kicked} receives the disconnect reason, if any. */
    private static Player player(UUID uuid, String name, String ip, boolean exempt,
                                 AtomicReference<Component> kicked) {
        return (Player) Proxy.newProxyInstance(PunishmentLoginListenerTest.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "getRemoteAddress" -> new InetSocketAddress(ip, 25565);
                    case "hasPermission" -> exempt && PunishPermissions.EXEMPT.equals(args[0]);
                    case "disconnect" -> {
                        kicked.set((Component) args[0]);
                        yield null;
                    }
                    case "toString" -> "Player[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Player player(UUID uuid, String name, String ip) {
        return player(uuid, name, ip, false, new AtomicReference<>());
    }

    private static InboundConnection connection(String ip) {
        return (InboundConnection) Proxy.newProxyInstance(PunishmentLoginListenerTest.class.getClassLoader(),
                new Class<?>[]{InboundConnection.class}, (proxy, method, args) ->
                        method.getName().equals("getRemoteAddress") ? new InetSocketAddress(ip, 25565) : null);
    }

    private static void run(EventTask task) {
        if (task != null) {
            task.execute((Continuation) Proxy.newProxyInstance(PunishmentLoginListenerTest.class.getClassLoader(),
                    new Class<?>[]{Continuation.class}, (p, m, a) -> null));
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private Punishment ban(Target target, IpMode mode, PunishmentScope scope) {
        return assertInstanceOf(me.ganamaga.katharsis_proxy.punish.IssueResult.Issued.class,
                service.issue(new PunishmentRequest(PunishmentType.BAN, target, mode, scope,
                        PunishmentLength.PERMANENT, "Cheating", null, STAFF, false, false, null))).punishment();
    }

    private PreLoginEvent preLogin(String name, String ip) {
        PreLoginEvent event = new PreLoginEvent(connection(ip), name);
        run(listener.onPreLogin(event));
        return event;
    }

    private LoginEvent login(Player player) {
        LoginEvent event = new LoginEvent(player);
        run(listener.onLogin(event));
        return event;
    }

    // --- tests -------------------------------------------------------------

    @Test
    @DisplayName("a banned offline-mode account is turned away before login, with the ban screen")
    void preLoginDeniesOfflineAccount() throws Exception {
        UUID uuid = TargetResolver.offlineUuid("Kraxie");
        history.recordJoin(uuid, "Kraxie", "10.0.0.1", NOW);
        Punishment ban = ban(new Target(uuid, "Kraxie", null), IpMode.ACCOUNT, PunishmentScope.global());

        PreLoginEvent event = preLogin("Kraxie", "10.0.0.2");

        assertFalse(event.getResult().isAllowed());
        String screen = plain(event.getResult().getReasonComponent().orElseThrow());
        assertTrue(screen.contains("You are banned"), screen);
        assertTrue(screen.contains("Cheating"), screen);
        assertTrue(screen.contains("#" + ban.id()), screen);
        assertTrue(screen.contains("permanent"), screen);
        assertTrue(screen.contains("discord.gg/appeal"), screen);
    }

    @Test
    @DisplayName("before login, premium names, address bans and scoped bans are left alone")
    void preLoginLeavesTheRest() throws Exception {
        UUID premium = UUID.randomUUID();
        history.recordJoin(premium, "Notchy", null, NOW);
        ban(new Target(premium, "Notchy", null), IpMode.ACCOUNT, PunishmentScope.global());
        ban(Target.address("10.0.0.3"), IpMode.IP_ONLY, PunishmentScope.global());
        UUID scoped = TargetResolver.offlineUuid("Scoped");
        history.recordJoin(scoped, "Scoped", null, NOW);
        ban(new Target(scoped, "Scoped", null), IpMode.ACCOUNT, PunishmentScope.server("lobby"));

        assertTrue(preLogin("Notchy", "10.0.0.9").getResult().isAllowed(), "a premium name may have changed hands");
        assertTrue(preLogin("Anyone", "10.0.0.3").getResult().isAllowed(), "addresses wait for login");
        assertTrue(preLogin("Scoped", "10.0.0.9").getResult().isAllowed());
    }

    @Test
    @DisplayName("at login a banned account is turned away, in its stored language")
    void loginDeniesAccountInItsLanguage() throws Exception {
        UUID uuid = UUID.randomUUID();
        players.createIfAbsent(uuid, "cs_CZ");
        ban(new Target(uuid, "Kraxie", null), IpMode.ACCOUNT, PunishmentScope.global());

        LoginEvent event = login(player(uuid, "Kraxie", "10.0.0.1"));

        assertFalse(event.getResult().isAllowed());
        assertTrue(plain(event.getResult().getReasonComponent().orElseThrow()).contains("Máš ban"));
        assertTrue(service.active().session(uuid).isEmpty(), "nothing is loaded for a player kept out");
    }

    @Test
    @DisplayName("at login a new account on a banned address is turned away, unless exempt")
    void loginDeniesAddress() {
        Punishment ban = ban(new Target(UUID.randomUUID(), "Main", "10.0.0.5"), IpMode.ACCOUNT_AND_IP,
                PunishmentScope.global());

        assertFalse(login(player(UUID.randomUUID(), "Alt", "10.0.0.5")).getResult().isAllowed());

        UUID staff = UUID.randomUUID();
        LoginEvent event = login(player(staff, "Staff", "10.0.0.5", true, new AtomicReference<>()));
        assertTrue(event.getResult().isAllowed());
        assertTrue(service.active().session(staff).orElseThrow().active().stream()
                .noneMatch(p -> p.id() == ban.id()), "the address ban is not held against them");
    }

    @Test
    @DisplayName("a player let in has what is in force against them loaded")
    void loginLoadsSession() {
        UUID uuid = UUID.randomUUID();
        Punishment scoped = ban(new Target(uuid, "Kraxie", null), IpMode.ACCOUNT, PunishmentScope.server("lobby"));

        assertTrue(login(player(uuid, "Kraxie", "10.0.0.6")).getResult().isAllowed());

        ActivePunishments.Session session = service.active().session(uuid).orElseThrow();
        assertEquals(List.of(scoped), session.active());
        assertEquals("10.0.0.6", session.ip());
    }

    @Test
    @DisplayName("a ban issued between login and joining disconnects the player once they are in")
    void postLoginCatchesLateBan() {
        UUID uuid = UUID.randomUUID();
        AtomicReference<Component> kicked = new AtomicReference<>();
        Player player = player(uuid, "Kraxie", "10.0.0.7", false, kicked);
        login(player);
        // Issued while the player is not yet reachable: only the generation shows it.
        service.active().remove(uuid, player);
        ban(new Target(uuid, "Kraxie", null), IpMode.ACCOUNT, PunishmentScope.global());
        service.active().put(uuid, new ActivePunishments.Session(player, "10.0.0.7", List.of(), 0));

        run(listener.onPostLogin(new PostLoginEvent(player)));

        assertNotNull(kicked.get());
        assertTrue(plain(kicked.get()).contains("You are banned"));
    }

    @Test
    @DisplayName("nothing to reload when nothing changed since login")
    void postLoginNoChange() {
        UUID uuid = UUID.randomUUID();
        Player player = player(uuid, "Kraxie", "10.0.0.8");
        login(player);
        assertNull(listener.onPostLogin(new PostLoginEvent(player)));
    }

    @Test
    @DisplayName("while bans cannot be checked, players are let in unless fail-closed is on")
    void databaseDown() {
        database.close();
        UUID uuid = UUID.randomUUID();

        assertTrue(login(player(uuid, "Kraxie", "10.0.0.9")).getResult().isAllowed());

        failClosed.set(true);
        LoginEvent refused = login(player(uuid, "Kraxie", "10.0.0.9"));
        assertFalse(refused.getResult().isAllowed());
        assertTrue(plain(refused.getResult().getReasonComponent().orElseThrow()).contains("cannot be checked"));
    }
}
