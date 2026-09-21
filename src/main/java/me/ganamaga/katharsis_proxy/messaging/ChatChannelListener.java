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
import me.ganamaga.katharsis_proxy.entity.FlaggedChatMessage;
import me.ganamaga.katharsis_proxy.moderation.ChatModerator;
import me.ganamaga.katharsis_proxy.moderation.ModerationAlerts;
import me.ganamaga.katharsis_proxy.moderation.ModerationNotice;
import me.ganamaga.katharsis_proxy.moderation.ModerationSource;
import me.ganamaga.katharsis_proxy.moderation.ModerationVerdict;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;

/**
 * Logs the chat reports backends send on the {@link ChatChannel#IDENTIFIER}
 * channel to the database, and hands each one to a {@link ChatRelay}.
 *
 * <p>Nothing is ever sent back. As with the language channel, only backend
 * servers are listened to: a client could otherwise put words in anyone's
 * mouth.</p>
 *
 * <h2>Moderation</h2>
 *
 * <p>With a {@link ChatModerator} in place, every report goes through it before
 * anything else happens to it, and only what comes back clean is relayed and
 * logged as chat. A message that is held back is logged as flagged instead,
 * reported to the staff channel, and its author is told — but it reaches
 * neither the relay nor the ordinary log.</p>
 *
 * <p>Moderation blocks on an HTTP call, so it and everything after it run on
 * the event task rather than the event thread. Relaying therefore happens a
 * little later than it used to, and two messages sent at almost the same moment
 * may reach Discord in the other order; what players saw in game is unaffected,
 * since the backend has already shown it to them by the time this runs.</p>
 */
public final class ChatChannelListener {

    private final Logger logger;
    private final DatabaseManager database;
    private final ChatMessageRepository chatMessages;
    private final ChatRelay relay;
    private final CompanionRegistry companions;
    private final Clock clock;

    /** Null while moderation is off, which relays and logs everything as before. */
    private final ChatModerator moderator;

    private final ModerationAlerts alerts;
    private final ModerationNotice notice;

    /** Without moderation: every report is relayed and logged. */
    public ChatChannelListener(Logger logger, DatabaseManager database, ChatMessageRepository chatMessages,
                               ChatRelay relay, CompanionRegistry companions) {
        this(logger, database, chatMessages, relay, companions, null, ModerationAlerts.NONE, ModerationNotice.NONE,
                Clock.systemUTC());
    }

    public ChatChannelListener(Logger logger, DatabaseManager database, ChatMessageRepository chatMessages,
                               ChatRelay relay, CompanionRegistry companions, ChatModerator moderator,
                               ModerationAlerts alerts, ModerationNotice notice) {
        this(logger, database, chatMessages, relay, companions, moderator, alerts, notice, Clock.systemUTC());
    }

    ChatChannelListener(Logger logger, DatabaseManager database, ChatMessageRepository chatMessages,
                        ChatRelay relay, CompanionRegistry companions, Clock clock) {
        this(logger, database, chatMessages, relay, companions, null, ModerationAlerts.NONE, ModerationNotice.NONE,
                clock);
    }

    ChatChannelListener(Logger logger, DatabaseManager database, ChatMessageRepository chatMessages,
                        ChatRelay relay, CompanionRegistry companions, ChatModerator moderator,
                        ModerationAlerts alerts, ModerationNotice notice, Clock clock) {
        this.logger = logger;
        this.database = database;
        this.chatMessages = chatMessages;
        this.relay = relay;
        this.companions = companions;
        this.moderator = moderator;
        this.alerts = alerts;
        this.notice = notice;
        this.clock = clock;
    }

    /** A decoded report: the message to log, and the server id to relay it under. */
    private record Report(ChatMessage message, String serverId) {
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
        Report report = decode(event.getData(), server);
        if (report == null) {
            return null;
        }
        // So Discord messages reach this backend under the id it reports as,
        // even if it has not announced since the proxy started.
        companions.recordServerId(server, report.serverId());

        if (moderator == null) {
            // Independent of the database, so chat still reaches Discord while it is down.
            relay.relay(report.serverId(), report.message());
            if (!database.isAvailable()) {
                // Nothing left to do, so no task is scheduled at all.
                unavailable(report.message());
                return null;
            }
            // Off the event thread: chat must not wait on the database.
            return EventTask.async(() -> store(report.message()));
        }
        // Off the event thread for the same reason, and all the more so: the
        // moderation call goes out over the network.
        return EventTask.async(() -> moderate(report));
    }

    /** Passes the report on, or holds it back and reports that instead. */
    private void moderate(Report report) {
        ChatMessage message = report.message();
        ModerationVerdict verdict;
        try {
            verdict = moderator.check(message.content());
        } catch (RuntimeException e) {
            // A filter that throws must not take the message with it, nor let
            // it past unchecked: it is held back like any other failure.
            logger.error("The chat filters failed on a message from {} ({}) on '{}'; it was held back.",
                    message.playerName(), message.playerUuid(), message.server(), e);
            verdict = ModerationVerdict.flagged(ModerationSource.UNAVAILABLE, e.toString());
        }

        if (verdict.isFlagged()) {
            hold(message, verdict);
            return;
        }
        relay.relay(report.serverId(), message);
        store(message);
    }

    /** Logs, reports and warns about a message that will not be passed on. */
    private void hold(ChatMessage message, ModerationVerdict verdict) {
        // At warn, and with the message itself: an admin reading the log is the
        // one person who needs to see exactly what was stopped.
        logger.warn("Held back a chat message from {} ({}) on '{}' [{}: {}]: {}",
                message.playerName(), message.playerUuid(), message.server(),
                verdict.source().id(), verdict.reason(), message.content());
        notice.held(message.playerUuid(), verdict);
        alerts.flagged(message, verdict);
        store(new FlaggedChatMessage(message, verdict.source().id(), verdict.reason()));
    }

    private void store(ChatMessage message) {
        if (unavailable(message)) {
            return;
        }
        try {
            chatMessages.insert(message);
        } catch (SQLException e) {
            logger.error("Could not log a chat message from {} ({}) on '{}'.",
                    message.playerName(), message.playerUuid(), message.server(), e);
        }
    }

    private void store(FlaggedChatMessage flagged) {
        if (unavailable(flagged.message())) {
            return;
        }
        try {
            chatMessages.insert(flagged);
        } catch (SQLException e) {
            logger.error("Could not log a held-back chat message from {} ({}) on '{}'.",
                    flagged.message().playerName(), flagged.message().playerUuid(), flagged.message().server(), e);
        }
    }

    /** Whether the log has to be skipped, saying so once per message at debug. */
    private boolean unavailable(ChatMessage message) {
        if (database.isAvailable()) {
            return false;
        }
        // Already reported loudly at startup; one line per chat message would bury the log.
        logger.debug("Dropped a chat message from {} on '{}': the database is unavailable.",
                message.playerName(), message.server());
        return true;
    }

    /**
     * Decodes a report into a message stamped with the time of receipt.
     *
     * @return the report, or {@code null} if the payload was malformed or not
     *         an action this proxy understands; the reason is logged
     */
    private Report decode(byte[] data, String server) {
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

        String serverId = server;
        if (json.has(ChatChannel.FIELD_SERVER)) {
            String reported = string(json, ChatChannel.FIELD_SERVER);
            if (reported == null) {
                logger.warn("Ignored a chat report from '{}': its server id is not a string.", server);
                return null;
            }
            if (!reported.isBlank()) {
                serverId = reported;
            }
        }

        try {
            // The database keeps the velocity.toml name, which a backend cannot
            // choose. The reported id only decides where the message is relayed.
            return new Report(new ChatMessage(UUID.fromString(uuid), playerName, server, content, clock.instant()),
                    serverId);
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
