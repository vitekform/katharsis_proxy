package me.ganamaga.katharsis_proxy.listener;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannel;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens the language selector for players joining the network for the first
 * time, so they pick a language straight away instead of having to discover
 * {@code /language}.
 *
 * <p>The selector lives in the companion plugin on the backend, so once such a
 * player has connected to their first server this pushes
 * {@link LanguageChannel#OP_OPEN_MENU} there. A backend without the companion
 * ignores it, and the player keeps the default language they were given.</p>
 */
public final class FirstJoinMenuListener {

    private final Logger logger;

    /** First-time players who have not reached a server yet. */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public FirstJoinMenuListener(Logger logger) {
        this.logger = logger;
    }

    /**
     * Marks {@code playerId} as new to the network. Called on login, which
     * Velocity completes before connecting the player to any server.
     */
    public void markFirstJoin(UUID playerId) {
        pending.add(playerId);
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (!pending.remove(player.getUniqueId())) {
            return;
        }
        Optional<ServerConnection> server = player.getCurrentServer();
        if (server.isEmpty() || !server.get().sendPluginMessage(LanguageChannel.IDENTIFIER, encode(player))) {
            logger.debug("Could not ask for the language selector for {}: they left the server.", player.getUsername());
            return;
        }
        logger.info("Opening the language selector for {} on '{}', their first time on the network.",
                player.getUsername(), server.get().getServerInfo().getName());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // A player who never reached a server is not new next time either.
        pending.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Laid out like a successful response to a request the backend never made,
     * so the companion decodes it with its usual reader: empty request id, the
     * player's uuid, and no extra fields.
     */
    static byte[] encode(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(LanguageChannel.OP_OPEN_MENU);
        out.writeUTF("");
        out.writeUTF(player.getUniqueId().toString());
        out.writeBoolean(true);
        out.writeUTF("");
        out.writeUTF(LanguageChannel.ERROR_NONE);
        return out.toByteArray();
    }

    /** Players waiting for their first server, for tests. */
    boolean isPending(UUID playerId) {
        return pending.contains(playerId);
    }
}
