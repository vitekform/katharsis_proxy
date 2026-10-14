package me.ganamaga.katharsis_neoforge.mute;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import me.ganamaga.katharsis_neoforge.protocol.PunishChannel;
import me.ganamaga.katharsis_neoforge.protocol.PunishCodec;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Enforces the mutes the proxy tells this server about over
 * {@code katharsis:punish}.
 *
 * <p>Mutes are issued on the proxy, but only the backend can stop a player's
 * chat: the proxy cannot cancel a signed chat message without disconnecting the
 * client. Chat and the blocked commands are cancelled at the highest priority,
 * so no other mod sees them and {@code ChatReporter} never reports them. The
 * reason is not shown here: the proxy tells the player, in their language,
 * when told they tried to speak.</p>
 */
public final class Mutes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ProxyLink link;
    private final MuteRegistry mutes = new MuteRegistry();

    public Mutes(ProxyLink link) {
        this.link = link;
        link.listen(ProxyPayload.PUNISH, this::received);
    }

    public void onChat(ServerChatEvent event) {
        if (muted(event.getPlayer()).isPresent()) {
            event.setCanceled(true);
            attempted(event.getPlayer());
        }
    }

    public void onCommand(CommandEvent event) {
        ServerPlayer player = event.getParseResults().getContext().getSource().getPlayer();
        if (player == null) {
            return;
        }
        Optional<MuteState> mute = muted(player);
        if (mute.isPresent() && mute.get().blocks(event.getParseResults().getReader().getString())) {
            event.setCanceled(true);
            attempted(player);
        }
    }

    /**
     * The proxy pushes the state as the player arrives; asking again covers
     * the push arriving before this server was ready for it.
     */
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            link.send(player, ProxyPayload.PUNISH, PunishCodec.encodeQuery(player.getUUID()));
        }
    }

    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        mutes.forget(event.getEntity().getUUID());
    }

    private void received(ServerPlayer carrier, byte[] message) {
        PunishCodec.Update update = PunishCodec.decodeState(message);
        if (update == null) {
            LOGGER.debug("Ignored a malformed message on {}.", PunishChannel.CHANNEL);
            return;
        }
        if (!update.uuid().equals(carrier.getUUID())) {
            // The proxy sends each state on its own player's connection.
            LOGGER.debug("Ignored a mute state for {} sent over {}'s connection.", update.uuid(),
                    carrier.getScoreboardName());
            return;
        }
        mutes.update(update.uuid(), update.state());
    }

    private Optional<MuteState> muted(ServerPlayer player) {
        return mutes.activeMute(player.getUUID(), System.currentTimeMillis());
    }

    private void attempted(ServerPlayer player) {
        link.send(player, ProxyPayload.PUNISH, PunishCodec.encodeAttempt(player.getUUID()));
    }
}
