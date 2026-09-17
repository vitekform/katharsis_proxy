package me.ganamaga.katharsis_proxy.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import me.ganamaga.katharsis_proxy.link.AccountLinkService;
import org.slf4j.Logger;

import java.sql.SQLException;

/**
 * Loads each joining player's account link into the cache, so checking it
 * while they play never touches the database.
 */
public final class AccountLinkJoinListener {

    private final Logger logger;
    private final AccountLinkService links;

    public AccountLinkJoinListener(Logger logger, AccountLinkService links) {
        this.logger = logger;
        this.links = links;
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        if (!links.isAvailable()) {
            return null;
        }
        var player = event.getPlayer();
        // Off the event thread: the login must not wait on the database.
        return EventTask.async(() -> {
            try {
                links.load(player.getUniqueId());
            } catch (SQLException | IllegalArgumentException e) {
                // Left uncached, so the next lookup tries the database again.
                logger.error("Could not load the account link of {} ({}).",
                        player.getUsername(), player.getUniqueId(), e);
            }
        });
    }
}
