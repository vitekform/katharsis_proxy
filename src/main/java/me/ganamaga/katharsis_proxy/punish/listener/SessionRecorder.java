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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records each session's account, name and address in the player history, so
 * players can be punished by name while offline and accounts sharing an
 * address can be found.
 *
 * <p>This waits for the first server connection of a session rather than the
 * login: a player reaches a server only once they are authenticated, so someone
 * trying a password account's name without its password never gets their
 * address recorded against that account.</p>
 *
 * <p>Once recorded, each {@link Inspector} looks the new session over, e.g.
 * for accounts it shares an address with.</p>
 */
public final class SessionRecorder {

    /** Looks at a session once it is recorded. Runs off the event thread. */
    @FunctionalInterface
    public interface Inspector {
        void inspect(Player player, String ip) throws SQLException;
    }

    private final Logger logger;
    private final PlayerHistoryRepository history;
    private final Clock clock;
    private final List<Inspector> inspectors = new CopyOnWriteArrayList<>();

    public SessionRecorder(Logger logger, PlayerHistoryRepository history, Clock clock) {
        this.logger = logger;
        this.history = history;
        this.clock = clock;
    }

    public void addInspector(Inspector inspector) {
        inspectors.add(inspector);
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
        return EventTask.async(() -> {
            if (record(uuid, name, ip)) {
                inspect(player, ip);
            }
        });
    }

    void inspect(Player player, String ip) {
        for (Inspector inspector : inspectors) {
            try {
                inspector.inspect(player, ip);
            } catch (SQLException | RuntimeException e) {
                logger.error("Could not look over the session of {}.", player.getUsername(), e);
            }
        }
    }

    /** @return whether it was recorded */
    boolean record(UUID uuid, String name, String ip) {
        try {
            history.recordJoin(uuid, name, ip, clock.instant());
            return true;
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Could not record the session of {} ({}) in the player history.", name, uuid, e);
            return false;
        }
    }
}
