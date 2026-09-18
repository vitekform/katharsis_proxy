package me.ganamaga.katharsis_proxy.listener;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstJoinMenuListenerTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    /**
     * {@code open_language_menu} for {@link #PLAYER}, as the proxy sends it.
     * The companion's {@code ProtocolCodecTest} decodes exactly these bytes.
     */
    static final String GOLDEN_OPEN_MENU = "00126f70656e5f6c616e67756167655f6d656e75000000243165316632623363"
            + "2d346435652d366637302d383139322d6133623463356436653766380100000000";

    private final FirstJoinMenuListener listener = new FirstJoinMenuListener(
            LoggerFactory.getLogger(FirstJoinMenuListenerTest.class));

    /** Every plugin message sent to the player's server. */
    private final List<byte[]> sent = new CopyOnWriteArrayList<>();

    private ServerConnection stubServer() {
        return (ServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendPluginMessage" -> {
                        assertEquals(LanguageChannel.IDENTIFIER, (ChannelIdentifier) args[0]);
                        sent.add((byte[]) args[1]);
                        yield true;
                    }
                    case "getServerInfo" -> new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25565));
                    case "toString" -> "StubServer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> null;
                });
    }

    private Player stubPlayer() {
        ServerConnection server = stubServer();
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> PLAYER;
                    case "getUsername" -> "Notch";
                    case "getCurrentServer" -> Optional.of(server);
                    case "toString" -> "StubPlayer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 2;
                    default -> null;
                });
    }

    @Test
    @DisplayName("a new player's first server is asked to open the selector, once")
    void opensOnFirstServerOnly() {
        Player player = stubPlayer();
        listener.markFirstJoin(PLAYER);

        listener.onServerPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(1, sent.size());
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN_OPEN_MENU), sent.getFirst());

        listener.onServerPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(1, sent.size(), "switching servers afterwards does not open it again");
    }

    @Test
    @DisplayName("a returning player's selector is not opened")
    void returningPlayersAreLeftAlone() {
        listener.onServerPostConnect(new ServerPostConnectEvent(stubPlayer(), null));

        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("leaving before reaching a server forgets the player")
    void disconnectForgets() {
        listener.markFirstJoin(PLAYER);

        listener.onDisconnect(new DisconnectEvent(stubPlayer(), DisconnectEvent.LoginStatus.PRE_SERVER_JOIN));

        assertFalse(listener.isPending(PLAYER));
    }
}
