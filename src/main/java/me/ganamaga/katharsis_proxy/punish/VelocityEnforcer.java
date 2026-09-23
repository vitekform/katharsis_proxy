package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Optional;

/**
 * Puts new punishments into effect on the players online: a network-wide ban
 * disconnects its account and everyone on its address, a kick disconnects its
 * target.
 */
public final class VelocityEnforcer implements PunishmentObserver {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PunishmentText text;
    private final Clock clock;

    public VelocityEnforcer(ProxyServer proxy, Logger logger, PunishmentText text, Clock clock) {
        this.proxy = proxy;
        this.logger = logger;
        this.text = text;
        this.clock = clock;
    }

    @Override
    public void issued(Punishment punishment) {
        if (punishment.type() == PunishmentType.KICK) {
            online(punishment).ifPresent(player -> player.disconnect(
                    text.kickScreen(text.messages().languageOf(player), punishment)));
        } else if (punishment.type() == PunishmentType.BAN && punishment.scope().isGlobal()) {
            disconnectHeld(punishment);
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
