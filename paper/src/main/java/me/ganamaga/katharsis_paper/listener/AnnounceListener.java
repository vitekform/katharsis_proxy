package me.ganamaga.katharsis_paper.listener;

import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Optional;

/**
 * Tells the proxy this server runs the companion and owns the language
 * selector.
 *
 * <p>The announcement is sent at startup, but a plugin message can only travel
 * on a player's connection, so "at startup" means <em>as soon as there is a
 * player to carry it</em>. On a normal boot that is the first join; on a reload
 * of an already-populated server it is immediate. Either way the proxy learns
 * about this server without anyone running a command.</p>
 *
 * <p>It is then repeated, at most once per {@link #ANNOUNCE_INTERVAL}, so that a
 * proxy which restarted finds out about this server again.</p>
 */
public final class AnnounceListener implements Listener {

    /** Minimum gap between announcements. */
    public static final Duration ANNOUNCE_INTERVAL = Duration.ofMinutes(1);

    private final Plugin plugin;
    private final ProxyClient client;
    private final String version;
    private final String serverId;

    private volatile long lastAnnouncedNanos;
    private volatile boolean announcedOnce;

    /**
     * @param serverId this server's configured {@code server-id}, sent along so
     *                 the proxy delivers the matching Discord channel here;
     *                 blank means the velocity.toml name
     */
    public AnnounceListener(Plugin plugin, ProxyClient client, String version, String serverId) {
        this.plugin = plugin;
        this.client = client;
        this.version = version;
        this.serverId = serverId;
    }

    /**
     * Announces now if anyone is online to carry the message.
     *
     * <p>Called once the plugin is enabled. On a cold start nobody is connected
     * yet and this does nothing; the first join takes care of it.</p>
     *
     * @return whether an announcement was actually sent
     */
    public boolean announceOnStartup() {
        Optional<? extends Player> carrier = plugin.getServer().getOnlinePlayers().stream().findFirst();
        if (carrier.isEmpty()) {
            plugin.getLogger().info(
                    "Waiting for the first player: the proxy can only be told about this server "
                            + "over a player's connection.");
            return false;
        }
        announce(carrier.get());
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        announce(event.getPlayer());
    }

    private void announce(Player carrier) {
        if (!shouldAnnounce()) {
            return;
        }
        client.announce(carrier, version, serverId).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                // Most likely an older proxy, or none at all. Try again on the
                // next join rather than giving up for good.
                allowRetry();
                plugin.getLogger().fine("The proxy did not acknowledge our announcement.");
                return;
            }
            // The backend cannot know its own name in velocity.toml, so the
            // proxy sends it back with the acknowledgement.
            String serverName = response.serverName();
            plugin.getLogger().info(serverName.isEmpty()
                    ? "Announced to the proxy; /language is served from this server."
                    : "Announced to the proxy as '" + serverName + "'; /language is served from this server.");
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
