package me.ganamaga.katharsis_neoforge.lock;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.ganamaga.katharsis_neoforge.protocol.AuthCodec;
import me.ganamaga.katharsis_neoforge.protocol.AuthPayload;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Holds one connection's player in place until the proxy says they have logged
 * in.
 *
 * <p>Sits in the connection's Netty pipeline just ahead of the server's own
 * packet handler, so it sees every packet the client sends, already decoded,
 * before anything acts on it, and drops what {@link PacketRules} does not let
 * through. It goes in while the connection is still being configured, before
 * the player exists in the world, so there is no moment in which a packet
 * could slip past.</p>
 *
 * <p>It is also where the proxy's {@code unlock} arrives: messages on
 * {@code katharsis:auth} are taken off the connection here, and never reach the
 * server or any other mod.</p>
 */
public final class Lock extends ChannelInboundHandlerAdapter {

    public static final String NAME = "katharsis_auth_lock";

    /** Vanilla's name for the handler that hands packets to the game. */
    public static final String PACKET_HANDLER = "packet_handler";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Connection connection;

    private volatile boolean locked = true;

    /** Null until the player is in the world; an unlock before that is ignored. */
    private volatile ServerPlayer player;

    public Lock(Connection connection) {
        this.connection = connection;
    }

    public boolean isLocked() {
        return locked;
    }

    void attach(ServerPlayer player) {
        this.player = player;
    }

    ServerPlayer player() {
        return player;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ServerboundCustomPayloadPacket custom && custom.payload() instanceof AuthPayload auth) {
            receive(auth);
            return;
        }
        if (message instanceof ServerboundCustomPayloadPacket custom && custom.payload() instanceof ProxyPayload) {
            // From the proxy, not the player: the inbox behind this takes it.
            super.channelRead(context, message);
            return;
        }
        if (locked && message instanceof Packet<?> packet && !passes(packet)) {
            return;
        }
        super.channelRead(context, message);
    }

    private boolean passes(Packet<?> packet) {
        String payload = packet instanceof ServerboundCustomPayloadPacket custom
                ? custom.payload().getClass().getSimpleName()
                : null;
        boolean configuring = connection.getPacketListener() instanceof ServerConfigurationPacketListener;
        return PacketRules.passes(packet.getClass().getSimpleName(), payload, configuring);
    }

    private void receive(AuthPayload payload) {
        AuthCodec.Incoming message = AuthCodec.decode(payload.data());
        ServerPlayer current = player;
        if (message == null || !AuthCodec.OP_UNLOCK.equals(message.operation())) {
            LOGGER.debug("Ignored an unknown message on katharsis:auth.");
            return;
        }
        if (current == null || !message.uuid().equals(current.getUUID())) {
            LOGGER.warn("Ignored an unlock for {}, which is not the player on this connection.", message.uuid());
            return;
        }
        if (locked) {
            locked = false;
            LOGGER.info("{} has logged in on the proxy and may play.", current.getScoreboardName());
        }
    }
}
