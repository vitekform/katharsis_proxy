package me.ganamaga.katharsis_proxy.punish.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.punish.ServerBans;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps players off the servers they are banned from.
 *
 * <p>A player joining the network whose first server is closed to them is
 * sent to the first server in Velocity's {@code try} list that is not, and
 * told why once they arrive; only if every server is closed to them are they
 * disconnected. A player already on a server who tries to switch to one closed
 * to them simply stays where they are. Everything here reads the sessions
 * loaded at login, never the database.</p>
 */
public final class PunishmentServerListener {

    private final Logger logger;
    private final ServerBans bans;

    /** What to tell players once they reach a server, having been sent there instead. */
    private final Map<UUID, List<Component>> pending = new ConcurrentHashMap<>();

    public PunishmentServerListener(Logger logger, ServerBans bans) {
        this.logger = logger;
        this.bans = bans;
    }

    @Subscribe(order = PostOrder.LATE)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> initial = event.getInitialServer();
        if (initial.isEmpty()) {
            return;
        }
        String name = initial.get().getServerInfo().getName();
        Optional<Punishment> ban = bans.banOn(player, name);
        if (ban.isEmpty()) {
            return;
        }
        Optional<RegisteredServer> fallback = bans.fallback(player, List.of(name));
        if (fallback.isPresent()) {
            logger.info("Sending {} to '{}' instead of '{}': banned there by #{}.", player.getUsername(),
                    fallback.get().getServerInfo().getName(), name, ban.get().id());
            event.setInitialServer(fallback.get());
            later(player, bans.bannedFromServer(player, ban.get(), name), bans.movedTo(player, fallback.get()));
        } else {
            refuse(player, ban.get(), name);
        }
    }

    /**
     * After the login checks, which run first. Also catches connections the
     * initial choice did not, such as Velocity trying the next server in the
     * list after the first failed.
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed() || event.getResult().getServer().isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        String name = event.getResult().getServer().get().getServerInfo().getName();
        Optional<Punishment> ban = bans.banOn(player, name);
        if (ban.isEmpty()) {
            return;
        }
        if (player.getCurrentServer().isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(bans.bannedFromServer(player, ban.get(), name));
            return;
        }
        // Joining the network: refusing would leave them nowhere.
        Optional<RegisteredServer> fallback = bans.fallback(player, List.of(name));
        if (fallback.isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(fallback.get()));
            later(player, bans.bannedFromServer(player, ban.get(), name), bans.movedTo(player, fallback.get()));
        } else {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            refuse(player, ban.get(), name);
        }
    }

    /** A player kicked from a server is not sent on to one they are banned from. */
    @Subscribe(order = PostOrder.LATE)
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!(event.getResult() instanceof KickedFromServerEvent.RedirectPlayer redirect)) {
            return;
        }
        Player player = event.getPlayer();
        String name = redirect.getServer().getServerInfo().getName();
        Optional<Punishment> ban = bans.banOn(player, name);
        if (ban.isEmpty()) {
            return;
        }
        Optional<RegisteredServer> fallback =
                bans.fallback(player, List.of(name, event.getServer().getServerInfo().getName()));
        if (fallback.isPresent()) {
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(fallback.get(), redirect.getMessageComponent()));
        } else {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(bans.screen(player, ban.get(), name)));
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        List<Component> messages = pending.remove(event.getPlayer().getUniqueId());
        if (messages != null) {
            messages.forEach(event.getPlayer()::sendMessage);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void later(Player player, Component... messages) {
        pending.computeIfAbsent(player.getUniqueId(), uuid -> Collections.synchronizedList(new ArrayList<>()))
                .addAll(List.of(messages));
    }

    private void refuse(Player player, Punishment ban, String server) {
        logger.info("Disconnecting {}: banned from '{}' by #{}, and no other server can take them.",
                player.getUsername(), server, ban.id());
        player.disconnect(bans.screen(player, ban, server));
    }
}
