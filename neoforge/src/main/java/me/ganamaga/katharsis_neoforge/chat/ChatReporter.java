package me.ganamaga.katharsis_neoforge.chat;

import me.ganamaga.katharsis_neoforge.protocol.ChatCodec;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Reports every chat message to the proxy on {@code katharsis:chat}, which logs
 * it to the database and posts it to Discord.
 *
 * <p>Registered at the lowest priority, not receiving cancelled events, so it
 * sees the message as it was finally sent, after other mods have had their
 * say, and skips messages that never appeared in chat — a muted player's
 * among them.</p>
 */
public final class ChatReporter {

    private final ProxyLink link;
    private final String serverId;

    /**
     * @param serverId sent with every report; blank sends none, and the proxy
     *                 uses this server's velocity.toml name
     */
    public ChatReporter(ProxyLink link, String serverId) {
        this.link = link;
        this.serverId = serverId;
    }

    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        // The author carries their own report.
        link.send(player, ProxyPayload.CHAT, ChatCodec.encodePlayerMessage(serverId, player.getUUID(),
                player.getScoreboardName(), event.getRawText()));
    }
}
