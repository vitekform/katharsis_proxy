package me.ganamaga.katharsis_proxy.punish.listener;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import me.ganamaga.katharsis_proxy.punish.ActivePunishments;
import me.ganamaga.katharsis_proxy.punish.Hierarchy;
import me.ganamaga.katharsis_proxy.punish.PunishmentService;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.ScopeResolver;
import me.ganamaga.katharsis_proxy.punish.ServerBans;
import me.ganamaga.katharsis_proxy.punish.VelocityEnforcer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentServerListenerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final Map<String, RegisteredServer> servers = new LinkedHashMap<>();
    private final List<Player> online = new ArrayList<>();
    private List<String> tryOrder = List.of("lobby", "lobby2");
    private PunishmentService service;
    private PunishmentServerListener listener;
    private VelocityEnforcer enforcer;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        for (String name : List.of("lobby", "lobby2", "lifesteal", "lifesteal2")) {
            servers.put(name, server(name));
        }
        ProxyServer proxy = proxy();
        LanguageRegistry registry = new LanguageRegistry(LoggerFactory.getLogger("test"), directory);
        registry.load("en_US");
        PunishmentText text = new PunishmentText(new Messages(registry, new PlayerLanguageCache()), () -> "");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // Only the in-memory sessions are read here.
        service = new PunishmentService(LoggerFactory.getLogger("test"), null, new ActivePunishments(),
                Hierarchy.NONE, ip -> false, clock);
        ServerBans bans = new ServerBans(proxy, service,
                new ScopeResolver(Map.of("survival", List.of("lifesteal", "lifesteal2"))), text);
        listener = new PunishmentServerListener(LoggerFactory.getLogger("test"), bans);
        enforcer = new VelocityEnforcer(proxy, LoggerFactory.getLogger("test"), text, clock, bans);
    }

    // --- stubs -------------------------------------------------------------

    private static RegisteredServer server(String name) {
        ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
        return (RegisteredServer) Proxy.newProxyInstance(PunishmentServerListenerTest.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> info;
                    case "toString" -> "Server[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private ProxyServer proxy() {
        ProxyConfig config = (ProxyConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyConfig.class}, (proxy, method, args) ->
                        method.getName().equals("getAttemptConnectionOrder") ? tryOrder : null);
        return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConfiguration" -> config;
                    case "getServer" -> Optional.ofNullable(servers.get((String) args[0]));
                    case "getAllPlayers" -> List.copyOf(online);
                    default -> null;
                });
    }

    /** A player on {@code current} (or none), recording what happens to them. */
    private final class StubPlayer {
        final UUID uuid = UUID.randomUUID();
        String current;
        final List<Component> messages = new ArrayList<>();
        Component kicked;
        String movedTo;
        final Player player;

        StubPlayer(String current) {
            this.current = current;
            this.player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getUsername" -> "Kraxie";
                        case "getCurrentServer" -> Optional.ofNullable(this.current).map(this::connection);
                        case "sendMessage" -> {
                            messages.add((Component) args[0]);
                            yield null;
                        }
                        case "disconnect" -> {
                            kicked = (Component) args[0];
                            yield null;
                        }
                        case "createConnectionRequest" -> request((RegisteredServer) args[0]);
                        case "hasPermission" -> false;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            service.active().put(uuid, new ActivePunishments.Session(player, null, List.of(), 0));
            online.add(player);
        }

        private ServerConnection connection(String name) {
            return (ServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ServerConnection.class}, (proxy, method, args) ->
                            method.getName().equals("getServerInfo") ? servers.get(name).getServerInfo() : null);
        }

        private ConnectionRequestBuilder request(RegisteredServer target) {
            return (ConnectionRequestBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ConnectionRequestBuilder.class}, (proxy, method, args) -> {
                        if (method.getName().equals("fireAndForget")) {
                            movedTo = target.getServerInfo().getName();
                        }
                        return method.getName().equals("connect") ? new CompletableFuture<>() : null;
                    });
        }

        void ban(long id, PunishmentScope scope) {
            Punishment ban = new Punishment(id, PunishmentType.BAN, uuid, "Kraxie", null, scope, "griefing", null,
                    IssuerRef.console(), false, null, NOW.minusSeconds(60), NOW.plusSeconds(3600), null);
            ActivePunishments.Session session = service.active().session(uuid).orElseThrow();
            List<Punishment> active = new ArrayList<>(session.active());
            active.add(ban);
            service.active().put(uuid, new ActivePunishments.Session(player, null, active, 0));
        }

        String said() {
            return String.join("\n", messages.stream().map(PunishmentServerListenerTest::plain).toList());
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // --- tests -------------------------------------------------------------

    @Test
    @DisplayName("a player whose first server is closed to them starts on the next one, and is told once there")
    void initialServerReplaced() {
        StubPlayer stub = new StubPlayer(null);
        stub.ban(1, PunishmentScope.server("lobby"));
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(stub.player, servers.get("lobby"));

        listener.onChooseInitialServer(event);

        assertSame(servers.get("lobby2"), event.getInitialServer().orElseThrow());
        assertTrue(stub.messages.isEmpty(), "told once they reach a server");
        listener.onServerPostConnect(new ServerPostConnectEvent(stub.player, null));
        assertTrue(stub.said().contains("banned from lobby"), stub.said());
        assertTrue(stub.said().contains("sent to lobby2"), stub.said());
    }

    @Test
    @DisplayName("with every server in the try list closed, the player is disconnected")
    void initialServerNoneLeft() {
        StubPlayer stub = new StubPlayer(null);
        stub.ban(1, PunishmentScope.server("lobby"));
        stub.ban(2, PunishmentScope.server("lobby2"));

        listener.onChooseInitialServer(new PlayerChooseInitialServerEvent(stub.player, servers.get("lobby")));

        assertNotNull(stub.kicked);
        assertTrue(plain(stub.kicked).contains("no other server can take you"), plain(stub.kicked));
    }

    @Test
    @DisplayName("an unbanned player's first server is left alone")
    void initialServerAllowed() {
        StubPlayer stub = new StubPlayer(null);
        stub.ban(1, PunishmentScope.server("lifesteal"));
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(stub.player, servers.get("lobby"));

        listener.onChooseInitialServer(event);

        assertSame(servers.get("lobby"), event.getInitialServer().orElseThrow());
    }

    @Test
    @DisplayName("switching to a server of a banned group is refused, and the player stays put")
    void switchRefused() {
        StubPlayer stub = new StubPlayer("lobby");
        stub.ban(1, PunishmentScope.group("survival"));
        ServerPreConnectEvent event = new ServerPreConnectEvent(stub.player, servers.get("lifesteal2"));

        listener.onServerPreConnect(event);

        assertFalse(event.getResult().isAllowed());
        assertTrue(stub.said().contains("banned from lifesteal2"), stub.said());
        assertNull(stub.kicked);
    }

    @Test
    @DisplayName("a first connection to a closed server is redirected rather than refused")
    void firstConnectionRedirected() {
        StubPlayer stub = new StubPlayer(null);
        stub.ban(1, PunishmentScope.server("lobby"));
        ServerPreConnectEvent event = new ServerPreConnectEvent(stub.player, servers.get("lobby"));

        listener.onServerPreConnect(event);

        assertTrue(event.getResult().isAllowed());
        assertSame(servers.get("lobby2"), event.getResult().getServer().orElseThrow());
    }

    @Test
    @DisplayName("a connection someone else already refused is left alone")
    void alreadyRefused() {
        StubPlayer stub = new StubPlayer("lobby");
        stub.ban(1, PunishmentScope.global());
        ServerPreConnectEvent event = new ServerPreConnectEvent(stub.player, servers.get("lifesteal"));
        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        listener.onServerPreConnect(event);

        assertTrue(stub.messages.isEmpty());
    }

    @Test
    @DisplayName("a kick never redirects to a closed server")
    void kickRedirect() {
        StubPlayer stub = new StubPlayer("lifesteal");
        stub.ban(1, PunishmentScope.server("lobby"));
        KickedFromServerEvent event = new KickedFromServerEvent(stub.player, servers.get("lifesteal"),
                Component.text("restarting"), false,
                KickedFromServerEvent.RedirectPlayer.create(servers.get("lobby"), Component.empty()));

        listener.onKickedFromServer(event);

        KickedFromServerEvent.RedirectPlayer redirect =
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
        assertSame(servers.get("lobby2"), redirect.getServer());

        stub.ban(2, PunishmentScope.server("lobby2"));
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(servers.get("lobby"), Component.empty()));
        listener.onKickedFromServer(event);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, event.getResult());
    }

    @Test
    @DisplayName("a new server ban moves players off it, or disconnects them with nowhere to go")
    void enforcerMovesPlayers() {
        StubPlayer onServer = new StubPlayer("lifesteal");
        StubPlayer elsewhere = new StubPlayer("lobby");
        onServer.ban(1, PunishmentScope.group("survival"));
        elsewhere.ban(1, PunishmentScope.group("survival"));
        Punishment issued = service.active().session(onServer.uuid).orElseThrow().active().get(0);

        enforcer.issued(issued);

        assertEquals("lobby", onServer.movedTo);
        assertTrue(onServer.said().contains("banned from lifesteal"), onServer.said());
        assertNull(elsewhere.movedTo, "not on a server the ban covers");
        assertTrue(elsewhere.messages.isEmpty());

        tryOrder = List.of("lifesteal2");
        StubPlayer stranded = new StubPlayer("lifesteal");
        stranded.ban(3, PunishmentScope.group("survival"));
        enforcer.issued(issued);
        assertNotNull(stranded.kicked);
    }
}
