package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.messaging.PunishChannel;
import me.ganamaga.katharsis_proxy.messaging.PunishCodec;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteEnforcerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final List<Player> online = new ArrayList<>();
    private final Set<String> proxyCommands = Set.of("msg", "lobby");
    private PunishmentService service;
    private MuteEnforcer enforcer;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        LanguageRegistry registry = new LanguageRegistry(LoggerFactory.getLogger("test"), directory);
        registry.load("en_US");
        PunishmentText text = new PunishmentText(new Messages(registry, new PlayerLanguageCache()), () -> "");
        service = new PunishmentService(LoggerFactory.getLogger("test"), null, new ActivePunishments(),
                Hierarchy.NONE, ip -> false, Clock.fixed(NOW, ZoneOffset.UTC));
        enforcer = new MuteEnforcer(proxy(), LoggerFactory.getLogger("test"), service,
                new ScopeResolver(Map.of("survival", List.of("lifesteal"))), text, PunishmentsConfig::new);
    }

    private ProxyServer proxy() {
        CommandManager commands = (CommandManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandManager.class}, (proxy, method, args) ->
                        method.getName().equals("hasCommand") ? proxyCommands.contains((String) args[0]) : null);
        return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAllPlayers" -> List.copyOf(online);
                    case "getPlayer" -> online.stream().filter(p -> p.getUniqueId().equals(args[0])).findFirst();
                    case "getCommandManager" -> commands;
                    default -> null;
                });
    }

    /** A player on {@code server}, recording chat and what their server is told. */
    private final class StubPlayer {
        final UUID uuid = UUID.randomUUID();
        final List<Component> chat = new ArrayList<>();
        final List<byte[]> pushed = new ArrayList<>();
        final Player player;
        final ServerConnection connection;

        StubPlayer(String server) {
            ServerInfo info = new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565));
            Player[] self = new Player[1];
            connection = (ServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getServerInfo" -> info;
                        case "getPlayer" -> self[0];
                        case "sendPluginMessage" -> {
                            assertEquals(PunishChannel.IDENTIFIER, args[0]);
                            pushed.add((byte[]) args[1]);
                            yield true;
                        }
                        default -> null;
                    });
            player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getCurrentServer" -> Optional.of(connection);
                        case "sendMessage" -> {
                            chat.add((Component) args[0]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            self[0] = player;
            service.active().put(uuid, new ActivePunishments.Session(player, null, List.of(), 0));
            online.add(player);
        }

        Punishment mute(long id, PunishmentScope scope, Instant expiresAt) {
            Punishment mute = new Punishment(id, PunishmentType.MUTE, uuid, "Kraxie", null, scope, "spam", null,
                    IssuerRef.console(), false, null, NOW.minusSeconds(60), expiresAt, null);
            List<Punishment> active = new ArrayList<>(service.active().session(uuid).orElseThrow().active());
            active.add(mute);
            service.active().put(uuid, new ActivePunishments.Session(player, null, active, 0));
            return mute;
        }

        String lastPush() {
            return HexFormat.of().formatHex(pushed.get(pushed.size() - 1));
        }

        String said() {
            return String.join("\n", chat.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                    .toList());
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    @DisplayName("reaching a server tells it whether the player is muted there, and what that blocks")
    void pushesOnConnect() {
        StubPlayer lobby = new StubPlayer("lobby");
        StubPlayer survival = new StubPlayer("lifesteal");
        lobby.mute(1, PunishmentScope.group("survival"), null);
        Punishment mute = survival.mute(2, PunishmentScope.group("survival"), null);

        enforcer.onServerPostConnect(new ServerPostConnectEvent(lobby.player, null));
        enforcer.onServerPostConnect(new ServerPostConnectEvent(survival.player, null));

        assertEquals(hex(PunishCodec.encodeMuteState(lobby.uuid, null, List.of())), lobby.lastPush(),
                "a group mute does not reach the lobby");
        assertEquals(hex(PunishCodec.encodeMuteState(survival.uuid, mute, new PunishmentsConfig().mutedCommands())),
                survival.lastPush());
    }

    @Test
    @DisplayName("the companion's question is answered on the same connection; its messages go no further")
    void answersQueries() {
        StubPlayer stub = new StubPlayer("lobby");
        PluginMessageEvent event = new PluginMessageEvent(stub.connection, stub.player, PunishChannel.IDENTIFIER,
                PunishCodecBytes.query(stub.uuid));

        enforcer.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed(), "handled, not forwarded to the client");
        assertEquals(1, stub.pushed.size());
    }

    @Test
    @DisplayName("a question about someone else, or from a client, is ignored")
    void ignoresMisaddressed() {
        StubPlayer stub = new StubPlayer("lobby");
        enforcer.onPluginMessage(new PluginMessageEvent(stub.connection, stub.player, PunishChannel.IDENTIFIER,
                PunishCodecBytes.query(UUID.randomUUID())));
        PluginMessageEvent fromClient = new PluginMessageEvent(stub.player, stub.connection,
                PunishChannel.IDENTIFIER, PunishCodecBytes.query(stub.uuid));
        enforcer.onPluginMessage(fromClient);

        assertTrue(stub.pushed.isEmpty());
        assertFalse(fromClient.getResult().isAllowed(), "not relayed either");
    }

    @Test
    @DisplayName("a muted attempt is answered with why, at most every few seconds")
    void remindsOnAttempt() {
        StubPlayer stub = new StubPlayer("lobby");
        stub.mute(9, PunishmentScope.global(), NOW.plusSeconds(3600));

        for (int i = 0; i < 3; i++) {
            enforcer.onPluginMessage(new PluginMessageEvent(stub.connection, stub.player, PunishChannel.IDENTIFIER,
                    PunishCodecBytes.attempt(stub.uuid)));
        }

        assertEquals(1, stub.chat.size());
        assertTrue(stub.said().contains("You are muted for 1h 0m: spam #9"), stub.said());
    }

    @Test
    @DisplayName("only the proxy's own talk commands are refused here")
    void refusesProxyCommands() {
        StubPlayer stub = new StubPlayer("lobby");
        stub.mute(1, PunishmentScope.global(), null);

        CommandExecuteEvent msg = new CommandExecuteEvent(stub.player, "msg Notch hi");
        CommandExecuteEvent tell = new CommandExecuteEvent(stub.player, "tell Notch hi");
        CommandExecuteEvent lobby = new CommandExecuteEvent(stub.player, "lobby");
        enforcer.onCommand(msg);
        enforcer.onCommand(tell);
        enforcer.onCommand(lobby);

        assertFalse(msg.getResult().isAllowed());
        assertTrue(tell.getResult().isAllowed(), "a backend command, left to the companion");
        assertTrue(lobby.getResult().isAllowed(), "not a talk command");
    }

    @Test
    @DisplayName("being muted, unmuted, or having it run out is pushed and told")
    void followsChanges() {
        StubPlayer stub = new StubPlayer("lobby");
        Punishment mute = stub.mute(1, PunishmentScope.global(), null);

        enforcer.issued(mute);
        assertEquals(1, stub.pushed.size());
        assertTrue(stub.said().contains("You were muted on whole network (permanent): spam"), stub.said());

        service.active().revoked(mute.id());
        enforcer.revoked(mute.withRevocation(new Punishment.Revocation(NOW, IssuerRef.console(), null)));
        assertEquals(2, stub.pushed.size());
        assertEquals(hex(PunishCodec.encodeMuteState(stub.uuid, null, List.of())), stub.lastPush());
        assertTrue(stub.said().endsWith("You can chat again."), stub.said());

        stub.mute(2, PunishmentScope.global(), NOW);
        enforcer.expired(service.active().evictExpired(NOW));
        assertEquals(3, stub.pushed.size());
    }

    @Test
    @DisplayName("namespaced aliases count as the command")
    void namespaces() {
        assertTrue(MuteEnforcer.isMutedCommand("minecraft:msg", List.of("msg")));
        assertTrue(MuteEnforcer.isMutedCommand("msg", List.of("msg")));
        assertFalse(MuteEnforcer.isMutedCommand("msgs", List.of("msg")));
        assertEquals("msg", MuteEnforcer.firstWord("  MSG Notch hi"));
    }

    /** What the companion sends, written as its codec writes it. */
    private static final class PunishCodecBytes {

        static byte[] query(UUID uuid) {
            return header(PunishChannel.OP_MUTE_QUERY, uuid);
        }

        static byte[] attempt(UUID uuid) {
            return header(PunishChannel.OP_MUTED_ATTEMPT, uuid);
        }

        private static byte[] header(String operation, UUID uuid) {
            var out = com.google.common.io.ByteStreams.newDataOutput();
            out.writeUTF(operation);
            out.writeUTF(uuid.toString());
            return out.toByteArray();
        }
    }
}
