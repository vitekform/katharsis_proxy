package me.ganamaga.katharsis_paper.listener;

import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;

/**
 * Tells the proxy this server owns the language selector.
 *
 * <p>A plugin message needs a player to travel on, so the announcement waits
 * for the first join. It is repeated, at most once per
 * {@link #ANNOUNCE_INTERVAL}, so that a proxy which restarted learns about this
 * server again without needing one here.</p>
 */
public final class AnnounceListener implements Listener {

    /** Minimum gap between announcements. */
    public static final Duration ANNOUNCE_INTERVAL = Duration.ofMinutes(1);

    private final Plugin plugin;
    private final ProxyClient client;
    private final String version;

    private volatile long lastAnnouncedNanos;
    private volatile boolean announcedOnce;

    public AnnounceListener(Plugin plugin, ProxyClient client, String version) {
        this.plugin = plugin;
        this.client = client;
        this.version = version;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!shouldAnnounce()) {
            return;
        }
        client.announce(event.getPlayer(), version).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                // Most likely an older proxy, or none at all. Try again on the
                // next join rather than giving up for good.
                allowRetry();
                plugin.getLogger().fine("The proxy did not acknowledge our announcement.");
            } else {
                plugin.getLogger().info("Announced to the proxy; /language is served from this server.");
            }
        });
    }

    private synchronized boolean shouldAnnounce() {
        long now = System.nanoTime();
        if (announcedOnce && now - lastAnnouncedNanos < ANNOUNCE_INTERVAL.toNanos()) {
            return false;
        }
        announcedOnce = true;
        lastAnnouncedNanos = now;
        return true;
    }

    private synchronized void allowRetry() {
        announcedOnce = false;
    }
}
