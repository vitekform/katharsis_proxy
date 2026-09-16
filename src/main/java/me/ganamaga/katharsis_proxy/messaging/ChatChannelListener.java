package me.ganamaga.katharsis_proxy.messaging;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import me.ganamaga.katharsis_proxy.database.ChatMessageRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;

/**
 * Logs the chat reports backends send on the {@link ChatChannel#IDENTIFIER}
 * channel to the database.
 *
 * <p>Nothing is ever sent back. As with the language channel, only backend
 * servers are listened to: a client could otherwise put words in anyone's
 * mouth.</p>
 */
public final class ChatChannelListener {

    private final Logger logger;
    private final DatabaseManager database;
    private final ChatMessageRepository chatMessages;
    private final Clock clock;

    public ChatChannelListener(Logger logger, DatabaseManager database, ChatMessageRepository chatMessages) {
        this(logger, database, chatMessages, Clock.systemUTC());
    }

    ChatChannelListener(Logger logger, DatabaseManager database, ChatMessageRepository chatMessages, Clock clock) {
        this.logger = logger;
        this.database = database;
        this.chatMessages = chatMessages;
        this.clock = clock;
    }

    @Subscribe
    public EventTask onPluginMessage(PluginMessageEvent event) {
        if (!ChatChannel.IDENTIFIER.equals(event.getIdentifier())) {
            return null;
        }

        // Proxy-internal: the player must never receive their own chat as JSON.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)) {
            return null;
        }

        String server = source.getServerInfo().getName();
        ChatMessage message = decode(event.getData(), server);
        if (message == null) {
            return null;
        }

        if (!database.isAvailable()) {
            // Already reported loudly at startup; one line per chat message would bury the log.
            logger.debug("Dropped a chat message from {} on '{}': the database is unavailable.",
                    message.playerName(), server);
            return null;
        }

        // Off the event thread: chat must not wait on the database.
        return EventTask.async(() -> store(message));
    }

    private void store(ChatMessage message) {
        try {
            chatMessages.insert(message);
        } catch (SQLException e) {
            logger.error("Could not log a chat message from {} ({}) on '{}'.",
                    message.playerName(), message.playerUuid(), message.server(), e);
        }
    }

    /**
     * Decodes a report into a message stamped with the time of receipt.
     *
     * @return the message, or {@code null} if the payload was malformed or not
     *         an action this proxy understands; the reason is logged
     */
    private ChatMessage decode(byte[] data, String server) {
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                logger.warn("Ignored a chat report from '{}': it is not a JSON object.", server);
                return null;
            }
            json = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            logger.warn("Ignored a chat report from '{}': it is not valid JSON.", server);
            return null;
        }

        String action = string(json, ChatChannel.FIELD_ACTION);
        if (!ChatChannel.ACTION_PLAYER_MSG.equals(action)) {
            logger.warn("Ignored a chat report from '{}' with unknown action '{}'.", server, action);
            return null;
        }

        String uuid = string(json, ChatChannel.FIELD_PLAYER);
        String playerName = string(json, ChatChannel.FIELD_PLAYER_NAME);
        String content = string(json, ChatChannel.FIELD_CONTENT);
        if (uuid == null || playerName == null || content == null) {
            logger.warn("Ignored a chat report from '{}': a required field is missing.", server);
            return null;
        }

        try {
            return new ChatMessage(UUID.fromString(uuid), playerName, server, content, clock.instant());
        } catch (IllegalArgumentException e) {
            // A malformed uuid, or a name too wide for its column.
            logger.warn("Ignored a chat report from '{}': {}", server, e.getMessage());
            return null;
        }
    }

    /** The field's value if it is a JSON string, otherwise {@code null}. */
    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }
}
