package me.ganamaga.katharsis_neoforge.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Takes the proxy's messages off one connection.
 *
 * <p>Sits in the Netty pipeline just ahead of the server's packet handler, like
 * the login lock, so NeoForge never sees these payloads: it would refuse them,
 * since the client never negotiated the channels. Each one is handed to
 * {@link ProxyLink} with the player whose connection it came on.</p>
 */
final class Inbox extends ChannelInboundHandlerAdapter {

    static final String NAME = "katharsis_inbox";

    private final ProxyLink link;

    /** Null until the player is in the world; a message before that is dropped. */
    private volatile ServerPlayer player;

    Inbox(ProxyLink link) {
        this.link = link;
    }

    void attach(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ServerboundCustomPayloadPacket custom && custom.payload() instanceof ProxyPayload payload) {
            link.received(player, payload);
            return;
        }
        super.channelRead(context, message);
    }
}
