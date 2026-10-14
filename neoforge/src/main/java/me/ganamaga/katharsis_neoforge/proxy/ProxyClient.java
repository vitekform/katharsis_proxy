package me.ganamaga.katharsis_neoforge.proxy;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.protocol.LanguageChannel;
import me.ganamaga.katharsis_neoforge.protocol.ProtocolCodec;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import me.ganamaga.katharsis_neoforge.protocol.ProxyResponse;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Asks the proxy things over {@code katharsis:language}, as the Paper
 * companion's class of the same name does.
 *
 * <p>Plugin messages are one-way, so each request carries an id that the proxy
 * echoes back; this class matches the reply to the {@link CompletableFuture}
 * waiting for it. A request that is never answered — an old proxy, or one
 * without the plugin — fails with a {@link TimeoutException} instead of hanging
 * the caller forever. Every future completes on the server thread, either
 * way.</p>
 */
public final class ProxyClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Prefixes request ids, as the plugin's name does on Paper. */
    private static final String REQUEST_PREFIX = "Katharsis-";

    private final ProxyLink link;
    private final long timeoutSeconds;
    private final AtomicLong nextRequestId = new AtomicLong();
    private final Map<String, CompletableFuture<ProxyResponse>> pending = new ConcurrentHashMap<>();

    /** Called when the proxy asks for the selector to be opened. */
    private volatile Consumer<ServerPlayer> openMenuHandler = player -> {
    };

    public ProxyClient(ProxyLink link, long timeoutSeconds) {
        this.link = link;
        this.timeoutSeconds = timeoutSeconds;
        link.listen(ProxyPayload.LANGUAGE, this::received);
    }

    /** Asks the proxy for everything needed to draw {@code player}'s selector. */
    public CompletableFuture<ProxyResponse> requestMenu(ServerPlayer player) {
        return send(player, LanguageChannel.OP_MENU, player.getUUID(), "");
    }

    /** Asks the proxy to store {@code language} for {@code player}. */
    public CompletableFuture<ProxyResponse> setLanguage(ServerPlayer player, String language) {
        return send(player, LanguageChannel.OP_SET, player.getUUID(), language);
    }

    /** Asks the proxy for everything needed to draw {@code player}'s settings. */
    public CompletableFuture<ProxyResponse> requestSettings(ServerPlayer player) {
        return send(player, LanguageChannel.OP_SETTINGS, player.getUUID(), "");
    }

    /** Asks the proxy to show or hide Discord chat in game for {@code player}. */
    public CompletableFuture<ProxyResponse> setDiscordChat(ServerPlayer player, boolean shown) {
        return send(player, LanguageChannel.OP_SET_DISCORD_CHAT, player.getUUID(), Boolean.toString(shown));
    }

    /**
     * Tells the proxy this server runs the companion and owns the selector.
     *
     * @param serverId this server's configured id, which tells the proxy which
     *                 Discord chat channel's messages belong here
     */
    public CompletableFuture<ProxyResponse> announce(ServerPlayer carrier, String version, String serverId) {
        return send(carrier, LanguageChannel.OP_ANNOUNCE,
                requestId -> ProtocolCodec.encodeAnnounce(requestId, version, serverId));
    }

    /**
     * Sets what to do when the proxy asks for {@code player}'s selector to be
     * opened, which it does on their first join to the network.
     */
    public void onOpenMenuRequest(Consumer<ServerPlayer> handler) {
        this.openMenuHandler = handler;
    }

    private CompletableFuture<ProxyResponse> send(ServerPlayer carrier, String operation, UUID subject,
                                                  String payload) {
        return send(carrier, operation,
                requestId -> ProtocolCodec.encodeRequest(operation, requestId, subject.toString(), payload));
    }

    private CompletableFuture<ProxyResponse> send(ServerPlayer carrier, String operation,
                                                  Function<String, byte[]> encode) {
        String requestId = REQUEST_PREFIX + nextRequestId.incrementAndGet();
        CompletableFuture<ProxyResponse> future = new CompletableFuture<>();
        pending.put(requestId, future);
        link.send(carrier, ProxyPayload.LANGUAGE, encode.apply(requestId));
        return future
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionallyCompose(failure -> CompletableFuture.failedFuture(failure instanceof TimeoutException
                        ? new TimeoutException("the proxy did not answer " + operation + " within "
                        + timeoutSeconds + "s")
                        : failure))
                // Whichever way it ends, it ends on the server thread.
                .whenCompleteAsync((response, failure) -> pending.remove(requestId), ProxyLink.server(carrier));
    }

    private void received(ServerPlayer carrier, byte[] message) {
        ProxyResponse response = ProtocolCodec.decodeResponse(message);
        if (response == null) {
            LOGGER.warn("Could not decode a reply from the proxy on {}.", LanguageChannel.CHANNEL);
            return;
        }

        if (LanguageChannel.OP_OPEN_MENU.equals(response.operation())) {
            // Unasked, so there is no request to match. It travels on the
            // connection of the player it is about.
            if (carrier.getUUID().toString().equals(response.uuid())) {
                openMenuHandler.accept(carrier);
            }
            return;
        }

        CompletableFuture<ProxyResponse> waiting = pending.remove(response.requestId());
        if (waiting != null) {
            waiting.complete(response);
        }
        // Otherwise a late reply whose request already timed out.
    }

    /** Drops every outstanding request, e.g. when the server stops. */
    public void cancelAll() {
        pending.values().removeIf(waiting -> {
            waiting.cancel(false);
            return true;
        });
    }
}
