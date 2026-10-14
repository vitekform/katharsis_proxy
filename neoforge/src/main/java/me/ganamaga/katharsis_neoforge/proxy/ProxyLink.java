package me.ganamaga.katharsis_neoforge.proxy;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelPipeline;
import me.ganamaga.katharsis_neoforge.lock.Lock;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's end of the proxy's companion channels: {@code katharsis:language},
 * {@code katharsis:chat} and {@code katharsis:punish}.
 *
 * <p>The same conversation the Paper companion has with the proxy, carried the
 * way {@code katharsis:auth} is: written straight onto the player's connection,
 * because NeoForge refuses to send on a channel the client did not negotiate,
 * and read off it by an {@link Inbox} before NeoForge sees it. Like every plugin
 * message, each one travels on some player's connection.</p>
 */
public final class ProxyLink {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Called on the server thread with the player whose connection the message came on. */
    @FunctionalInterface
    public interface Receiver {
        void receive(ServerPlayer carrier, byte[] data);
    }

    private final Map<CustomPacketPayload.Type<ProxyPayload>, Receiver> receivers = new ConcurrentHashMap<>();

    /** Hands what arrives on {@code channel} to {@code receiver}. */
    public void listen(CustomPacketPayload.Type<ProxyPayload> channel, Receiver receiver) {
        receivers.put(channel, receiver);
    }

    /** Sends {@code data} to the proxy on {@code channel}, over {@code carrier}'s connection. Callable from any thread. */
    public void send(ServerPlayer carrier, CustomPacketPayload.Type<ProxyPayload> channel, byte[] data) {
        carrier.connection.getConnection().send(new ClientboundCustomPayloadPacket(new ProxyPayload(channel, data)));
    }

    /** While the connection is still being configured, so no message can arrive ahead of the inbox. */
    public void onConfiguration(RegisterConfigurationTasksEvent event) {
        ChannelPipeline pipeline = event.getListener().getConnection().channel().pipeline();
        if (pipeline.get(Inbox.NAME) != null) {
            return;
        }
        try {
            pipeline.addBefore(Lock.PACKET_HANDLER, Inbox.NAME, new Inbox(this));
        } catch (NoSuchElementException | IllegalArgumentException e) {
            // Unlike the lock this protects nothing; the player just goes without the companion's features.
            LOGGER.warn("Could not listen for the proxy on a connection; its menus and mutes will not work.", e);
        }
    }

    /** Lets the player's messages through, now that there is a player to hand them over with. */
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Connection connection = player.connection.getConnection();
            if (connection.channel().pipeline().get(Inbox.NAME) instanceof Inbox inbox) {
                inbox.attach(player);
            }
        }
    }

    /** From the network thread. */
    void received(ServerPlayer player, ProxyPayload payload) {
        Receiver receiver = receivers.get(payload.type());
        if (player == null || receiver == null) {
            LOGGER.debug("Ignored a message on {} that arrived before the player joined.", payload.type().id());
            return;
        }
        server(player).execute(() -> receiver.receive(player, payload.data()));
    }

    /** The server {@code player} is on. */
    public static MinecraftServer server(ServerPlayer player) {
        // Typed as Level: 26.x narrows level() to ServerLevel, and a cast would be redundant there.
        Level level = player.level();
        return level.getServer();
    }
}
