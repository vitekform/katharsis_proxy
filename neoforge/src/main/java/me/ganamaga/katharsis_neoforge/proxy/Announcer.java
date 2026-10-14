package me.ganamaga.katharsis_neoforge.proxy;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Tells the proxy this server runs the companion and owns the language
 * selector, as the Paper companion does.
 *
 * <p>A plugin message can only travel on a player's connection, so the first
 * join sends it. It is then repeated on join, at most once per
 * {@link #ANNOUNCE_INTERVAL}, so that a proxy which restarted finds out about
 * this server again.</p>
 */
public final class Announcer {

    /** Minimum gap between announcements. */
    public static final Duration ANNOUNCE_INTERVAL = Duration.ofMinutes(1);

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ProxyClient client;
    private final String version;
    private final String serverId;

    private long lastAnnouncedNanos;
    private boolean announcedOnce;

    /**
     * @param serverId this server's configured {@code server-id}, sent along so
     *                 the proxy delivers the matching Discord channel here;
     *                 blank means the velocity.toml name
     */
    public Announcer(ProxyClient client, String version, String serverId) {
        this.client = client;
        this.version = version;
        this.serverId = serverId;
    }

    /** Says, once, why nothing is announced until somebody joins. */
    public void onServerStarted() {
        LOGGER.info("Waiting for the first player: the proxy can only be told about this server "
                + "over a player's connection.");
    }

    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldAnnounce()) {
            client.announce(player, version, serverId).whenComplete((response, failure) -> {
                if (failure != null || response == null || !response.success()) {
                    // Most likely an older proxy, or none at all. Try again on
                    // the next join rather than giving up for good.
                    announcedOnce = false;
                    LOGGER.debug("The proxy did not acknowledge our announcement.");
                    return;
                }
                // The backend cannot know its own name in velocity.toml, so the
                // proxy sends it back with the acknowledgement.
                String serverName = response.serverName();
                LOGGER.info(serverName.isEmpty()
                        ? "Announced to the proxy; /language is served from this server."
                        : "Announced to the proxy as '" + serverName + "'; /language is served from this server.");
            });
        }
    }

    /** Only on the server thread, as are the replies. */
    private boolean shouldAnnounce() {
        long now = System.nanoTime();
        if (announcedOnce && now - lastAnnouncedNanos < ANNOUNCE_INTERVAL.toNanos()) {
            return false;
        }
        announcedOnce = true;
        lastAnnouncedNanos = now;
        return true;
    }
}
