package me.ganamaga.katharsis_proxy.punish.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.punish.IpAddresses;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;

/**
 * Records each session's account, name and address in the player history, so
 * players can be punished by name while offline and accounts sharing an
 * address can be found.
 *
 * <p>This waits for the first server connection of a session rather than the
 * login: a player reaches a server only once they are authenticated, so someone
 * trying a password account's name without its password never gets their
 * address recorded against that account.</p>
 */
public final class SessionRecorder {

    private final Logger logger;
    private final PlayerHistoryRepository history;
    private final Clock clock;

    public SessionRecorder(Logger logger, PlayerHistoryRepository history, Clock clock) {
        this.logger = logger;
        this.history = history;
        this.clock = clock;
    }

    @Subscribe
    public EventTask onServerPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null) {
            return null; // a server switch, not a new session
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();
        String ip = IpAddresses.of(player.getRemoteAddress()).orElse(null);
        return EventTask.async(() -> record(uuid, name, ip));
    }

    void record(UUID uuid, String name, String ip) {
        try {
            history.recordJoin(uuid, name, ip, clock.instant());
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Could not record the session of {} ({}) in the player history.", name, uuid, e);
        }
    }
}
