package me.ganamaga.katharsis_paper.protocol;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Talks to the proxy over the plugin message channel.
 *
 * <p>Plugin messages are one-way, so each request carries an id that the proxy
 * echoes back; this class matches the reply to the {@link CompletableFuture}
 * waiting for it. A request that is never answered — an old proxy, or one
 * without the plugin — fails with a {@link TimeoutException} instead of hanging
 * the caller forever.</p>
 */
public final class ProxyClient implements PluginMessageListener {

    /** Ticks per second, for turning the configured timeout into a delay. */
    private static final long TICKS_PER_SECOND = 20L;

    private final Plugin plugin;
    private final long timeoutSeconds;
    private final AtomicLong nextRequestId = new AtomicLong();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ProxyClient(Plugin plugin, long timeoutSeconds) {
        this.plugin = plugin;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Asks the proxy for everything needed to draw {@code player}'s selector.
     *
     * @return the reply, completing on the server thread
     */
    public CompletableFuture<ProxyResponse> requestMenu(Player player) {
        return send(player, LanguageChannel.OP_MENU, player.getUniqueId(), "");
    }

    /** Asks the proxy to store {@code language} for {@code player}. */
    public CompletableFuture<ProxyResponse> setLanguage(Player player, String language) {
        return send(player, LanguageChannel.OP_SET, player.getUniqueId(), language);
    }

    /**
     * Tells the proxy this server owns the selector.
     *
     * <p>A plugin message needs a player to travel on, so this can only be sent
     * once somebody is connected — hence the call from the join listener.</p>
     */
    public CompletableFuture<ProxyResponse> announce(Player carrier, String version) {
        return send(carrier, LanguageChannel.OP_ANNOUNCE, new UUID(0L, 0L), version);
    }

    private CompletableFuture<ProxyResponse> send(Player carrier, String operation, UUID subject, String payload) {
        String requestId = plugin.getName() + "-" + nextRequestId.incrementAndGet();
        CompletableFuture<ProxyResponse> future = new CompletableFuture<>();

        BukkitTask timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Pending expired = pending.remove(requestId);
            if (expired != null) {
                expired.future().completeExceptionally(new TimeoutException(
                        "the proxy did not answer " + operation + " within " + timeoutSeconds + "s"));
            }
        }, timeoutSeconds * TICKS_PER_SECOND);

        pending.put(requestId, new Pending(future, timeout));
        carrier.sendPluginMessage(plugin, LanguageChannel.CHANNEL,
                ProtocolCodec.encodeRequest(operation, requestId, subject.toString(), payload));
        return future;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!LanguageChannel.CHANNEL.equals(channel)) {
            return;
        }

        ProxyResponse response = ProtocolCodec.decodeResponse(message);
        if (response == null) {
            plugin.getLogger().warning("Could not decode a reply from the proxy on " + channel + ".");
            return;
        }

        Pending waiting = pending.remove(response.requestId());
        if (waiting == null) {
            // A late reply whose request already timed out, or one meant for
            // another plugin sharing the channel.
            return;
        }
        waiting.timeout().cancel();
        waiting.future().complete(response);
    }

    /** Drops every outstanding request, e.g. on disable. */
    public void cancelAll() {
        pending.values().removeIf(waiting -> {
            waiting.timeout().cancel();
            waiting.future().cancel(false);
            return true;
        });
    }

    private record Pending(CompletableFuture<ProxyResponse> future, BukkitTask timeout) {
    }
}
