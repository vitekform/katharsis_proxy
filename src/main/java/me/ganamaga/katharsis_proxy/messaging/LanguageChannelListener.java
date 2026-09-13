package me.ganamaga.katharsis_proxy.messaging;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.entity.Player;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves {@code get_language}, {@code set_language} and {@code clear_language}
 * over the {@link LanguageChannel#IDENTIFIER} plugin message channel.
 *
 * <p>Only backend servers are answered. A message arriving from a player is
 * dropped, since a client could otherwise rewrite anyone's preferences.</p>
 */
public final class LanguageChannelListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final LanguageRegistry registry;
    private final DatabaseManager database;
    private final PlayerRepository players;
    private final PlayerLanguageCache cache;

    public LanguageChannelListener(ProxyServer proxy, Logger logger, LanguageRegistry registry,
                                   DatabaseManager database, PlayerRepository players,
                                   PlayerLanguageCache cache) {
        this.proxy = proxy;
        this.logger = logger;
        this.registry = registry;
        this.database = database;
        this.players = players;
        this.cache = cache;
    }

    @Subscribe
    public EventTask onPluginMessage(PluginMessageEvent event) {
        if (!LanguageChannel.IDENTIFIER.equals(event.getIdentifier())) {
            return null;
        }

        // This channel is proxy-internal, so never relay it to the other side.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)) {
            // A client speaking our channel. Ignore it rather than answering.
            return null;
        }

        Request request = decode(event.getData());
        if (request == null) {
            respond(source, new Response("", "", "", false, "", LanguageChannel.ERROR_MALFORMED_REQUEST));
            return null;
        }

        // Off the event thread: the database must not stall message handling.
        return EventTask.async(() -> respond(source, handle(request)));
    }

    private Response handle(Request request) {
        UUID playerId;
        try {
            playerId = UUID.fromString(request.uuid());
        } catch (IllegalArgumentException e) {
            return request.failure(LanguageChannel.ERROR_INVALID_UUID);
        }

        return switch (request.operation()) {
            case LanguageChannel.OP_GET -> getLanguage(request, playerId);
            case LanguageChannel.OP_SET -> setLanguage(request, playerId);
            case LanguageChannel.OP_CLEAR -> clearLanguage(request, playerId);
            default -> request.failure(LanguageChannel.ERROR_UNKNOWN_OPERATION);
        };
    }

    private Response getLanguage(Request request, UUID playerId) {
        Optional<String> cached = cache.get(playerId);
        if (cached.isPresent()) {
            return request.success(cached.get());
        }
        if (!database.isAvailable()) {
            return request.failure(LanguageChannel.ERROR_DATABASE_UNAVAILABLE);
        }
        try {
            Optional<Player> stored = players.find(playerId);
            if (stored.isEmpty()) {
                return request.failure(LanguageChannel.ERROR_UNKNOWN_PLAYER);
            }
            String language = stored.get().language();
            cacheFor(playerId, language);
            return request.success(language);
        } catch (SQLException e) {
            logger.error("{} failed for {}.", request.operation(), playerId, e);
            return request.failure(LanguageChannel.ERROR_DATABASE_ERROR);
        }
    }

    private Response setLanguage(Request request, UUID playerId) {
        String language = LanguageRegistry.normalise(request.language());
        if (language.isEmpty() || !registry.isKnown(language)) {
            return request.failure(LanguageChannel.ERROR_UNKNOWN_LANGUAGE);
        }
        return store(request, playerId, language);
    }

    private Response clearLanguage(Request request, UUID playerId) {
        // "Cleared" means back to the configured default, so a player with no
        // row is already clear and none is created for them.
        return store(request, playerId, registry.defaultLanguage());
    }

    private Response store(Request request, UUID playerId, String language) {
        if (!database.isAvailable()) {
            return request.failure(LanguageChannel.ERROR_DATABASE_UNAVAILABLE);
        }
        try {
            if (!players.updateLanguage(playerId, language)
                    && LanguageChannel.OP_SET.equals(request.operation())) {
                // Setting a language for someone with no row yet, e.g. a player
                // who has not joined since the plugin was installed.
                players.createIfAbsent(playerId, language);
            }
            cacheFor(playerId, language);
            return request.success(language);
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("{} failed for {}.", request.operation(), playerId, e);
            return request.failure(LanguageChannel.ERROR_DATABASE_ERROR);
        }
    }

    /**
     * Caches a language looked up through the channel. An offline player's entry
     * gets the normal retention window rather than being pinned, so querying
     * players who never join cannot grow the cache without bound.
     */
    private void cacheFor(UUID playerId, String language) {
        cache.put(playerId, language);
        if (proxy.getPlayer(playerId).isEmpty()) {
            cache.expireLater(playerId);
        }
    }

    private Request decode(byte[] data) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            String operation = in.readUTF();
            String requestId = in.readUTF();
            String uuid = in.readUTF();
            String language = LanguageChannel.OP_SET.equals(operation) ? in.readUTF() : "";
            return new Request(operation, requestId, uuid, language);
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            // Guava wraps a truncated buffer in an unchecked exception.
            return null;
        }
    }

    private void respond(ServerConnection source, Response response) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(response.operation());
        out.writeUTF(response.requestId());
        out.writeUTF(response.uuid());
        out.writeBoolean(response.success());
        out.writeUTF(response.language());
        out.writeUTF(response.error());

        if (!source.sendPluginMessage(LanguageChannel.IDENTIFIER, out.toByteArray())) {
            logger.warn("Could not answer {} on {}: the server connection is gone.",
                    source.getServerInfo().getName(), LanguageChannel.IDENTIFIER.getId());
        }
    }

    /** A decoded request. */
    private record Request(String operation, String requestId, String uuid, String language) {

        Response success(String language) {
            return new Response(operation, requestId, uuid, true, language, LanguageChannel.ERROR_NONE);
        }

        Response failure(String error) {
            return new Response(operation, requestId, uuid, false, "", error);
        }
    }

    /** A response, echoing the request header so replies can be correlated. */
    private record Response(String operation, String requestId, String uuid,
                            boolean success, String language, String error) {
    }
}
