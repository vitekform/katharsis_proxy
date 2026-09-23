package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Puts new punishments into effect on the players online: a network-wide ban
 * disconnects its account and everyone on its address, a ban on a server or
 * group moves them off it, and a kick disconnects its target.
 */
public final class VelocityEnforcer implements PunishmentObserver {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PunishmentText text;
    private final Clock clock;
    private final ServerBans bans;

    public VelocityEnforcer(ProxyServer proxy, Logger logger, PunishmentText text, Clock clock, ServerBans bans) {
        this.proxy = proxy;
        this.logger = logger;
        this.text = text;
        this.clock = clock;
        this.bans = bans;
    }

    @Override
    public void issued(Punishment punishment) {
        if (punishment.type() == PunishmentType.KICK) {
            online(punishment).ifPresent(player -> player.disconnect(
                    text.kickScreen(text.messages().languageOf(player), punishment)));
        } else if (punishment.type() == PunishmentType.BAN && punishment.scope().isGlobal()) {
            disconnectHeld(punishment);
        } else if (punishment.type() == PunishmentType.BAN) {
            moveOffBannedServers();
        }
    }

    /**
     * Sends everyone on a server they are now banned from to the first server
     * they may still join. Their sessions already carry the new ban.
     */
    private void moveOffBannedServers() {
        for (Player player : proxy.getAllPlayers()) {
            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isEmpty()) {
                continue;
            }
            String server = current.get().getServerInfo().getName();
            Optional<Punishment> holding = bans.banOn(player, server);
            if (holding.isEmpty()) {
                continue;
            }
            player.sendMessage(bans.bannedFromServer(player, holding.get(), server));
            Optional<RegisteredServer> fallback = bans.fallback(player, List.of(server));
            if (fallback.isPresent()) {
                logger.info("Moving {} from '{}' to '{}': banned there by #{}.", player.getUsername(), server,
                        fallback.get().getServerInfo().getName(), holding.get().id());
                player.createConnectionRequest(fallback.get()).fireAndForget();
                player.sendMessage(bans.movedTo(player, fallback.get()));
            } else {
                logger.info("Disconnecting {}: banned from '{}' by #{}, and no other server can take them.",
                        player.getUsername(), server, holding.get().id());
                player.disconnect(bans.screen(player, holding.get(), server));
            }
        }
    }

    private void disconnectHeld(Punishment ban) {
        for (Player player : proxy.getAllPlayers()) {
            if (!(player.getUniqueId().equals(ban.targetUuid()) || isOnBannedAddress(player, ban))) {
                continue;
            }
            logger.info("Disconnecting {} ({}): banned by #{}.", player.getUsername(), player.getUniqueId(), ban.id());
            player.disconnect(text.banScreen(text.messages().languageOf(player), ban, clock.instant()));
        }
    }

    /** On the banned address, and not the one staff member it may not touch. */
    private static boolean isOnBannedAddress(Player player, Punishment ban) {
        return ban.targetIp() != null
                && ban.targetIp().equals(IpAddresses.of(player.getRemoteAddress()).orElse(null))
                && !player.hasPermission(PunishPermissions.EXEMPT);
    }

    private Optional<Player> online(Punishment punishment) {
        return punishment.targetUuid() == null ? Optional.empty() : proxy.getPlayer(punishment.targetUuid());
    }
}
