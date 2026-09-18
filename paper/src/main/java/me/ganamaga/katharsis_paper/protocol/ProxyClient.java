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
import java.util.function.Consumer;
import java.util.function.Function;

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

    /** Called when the proxy asks for the selector to be opened. */
    private volatile Consumer<Player> openMenuHandler = player -> {
    };

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
     *
     * @param serverId this server's configured id, which tells the proxy which
     *                 Discord chat channel's messages belong here
     */
    public CompletableFuture<ProxyResponse> announce(Player carrier, String version, String serverId) {
        return send(carrier, LanguageChannel.OP_ANNOUNCE,
                requestId -> ProtocolCodec.encodeAnnounce(requestId, version, serverId));
    }

    private CompletableFuture<ProxyResponse> send(Player carrier, String operation, UUID subject, String payload) {
        return send(carrier, operation,
                requestId -> ProtocolCodec.encodeRequest(operation, requestId, subject.toString(), payload));
    }

    private CompletableFuture<ProxyResponse> send(Player carrier, String operation,
                                                  Function<String, byte[]> encode) {
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
        carrier.sendPluginMessage(plugin, LanguageChannel.CHANNEL, encode.apply(requestId));
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

        if (LanguageChannel.OP_OPEN_MENU.equals(response.operation())) {
            // Unasked, so there is no request to match. It travels on the
            // connection of the player it is about.
            if (player.getUniqueId().toString().equals(response.uuid())) {
                openMenuHandler.accept(player);
            }
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

    /**
     * Sets what to do when the proxy asks for {@code player}'s selector to be
     * opened, which it does on their first join to the network. Runs on the
     * server thread.
     */
    public void onOpenMenuRequest(Consumer<Player> handler) {
        this.openMenuHandler = handler;
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
