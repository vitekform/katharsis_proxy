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
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerDiscordChatCache;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves the language API over the {@link LanguageChannel#IDENTIFIER} plugin
 * message channel.
 *
 * <p>Only backend servers are answered. A message arriving from a player is
 * dropped, since a client could otherwise rewrite anyone's preferences.</p>
 */
public final class LanguageChannelListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final LanguageRegistry registry;
    private final Messages messages;
    private final DatabaseManager database;
    private final PlayerRepository players;
    private final PlayerLanguageCache cache;
    private final PlayerDiscordChatCache discordChat;
    private final CompanionRegistry companions;

    public LanguageChannelListener(ProxyServer proxy, Logger logger, LanguageRegistry registry,
                                   Messages messages, DatabaseManager database, PlayerRepository players,
                                   PlayerLanguageCache cache, PlayerDiscordChatCache discordChat,
                                   CompanionRegistry companions) {
        this.proxy = proxy;
        this.logger = logger;
        this.registry = registry;
        this.messages = messages;
        this.database = database;
        this.players = players;
        this.cache = cache;
        this.discordChat = discordChat;
        this.companions = companions;
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
            respond(source, Response.failed("", "", "", LanguageChannel.ERROR_MALFORMED_REQUEST));
            return null;
        }

        // Off the event thread: the database must not stall message handling.
        return EventTask.async(() -> respond(source, handle(request, source)));
    }

    private Response handle(Request request, ServerConnection source) {
        return switch (request.operation()) {
            case LanguageChannel.OP_ANNOUNCE -> announce(request, source);
            case LanguageChannel.OP_GET -> forPlayer(request, this::getLanguage);
            case LanguageChannel.OP_SET -> forPlayer(request, this::setLanguage);
            case LanguageChannel.OP_CLEAR -> forPlayer(request, this::clearLanguage);
            case LanguageChannel.OP_MENU -> forPlayer(request, this::menu);
            case LanguageChannel.OP_SETTINGS -> forPlayer(request, this::settings);
            case LanguageChannel.OP_SET_DISCORD_CHAT -> forPlayer(request, this::setDiscordChat);
            default -> request.failure(LanguageChannel.ERROR_UNKNOWN_OPERATION);
        };
    }

    /** Runs {@code action} once the request's uuid field has parsed. */
    private Response forPlayer(Request request, PlayerAction action) {
        UUID playerId;
        try {
            playerId = UUID.fromString(request.uuid());
        } catch (IllegalArgumentException e) {
            return request.failure(LanguageChannel.ERROR_INVALID_UUID);
        }
        return action.apply(request, playerId);
    }

    @FunctionalInterface
    private interface PlayerAction {
        Response apply(Request request, UUID playerId);
    }

    // --- operations --------------------------------------------------------

    /**
     * Acknowledges a companion and tells it which server the proxy knows it as.
     *
     * <p>A backend cannot discover its own name in velocity.toml, so the reply
     * carries it: that is the only way the companion's log can say which server
     * it announced as.</p>
     */
    private Response announce(Request request, ServerConnection source) {
        String serverName = source.getServerInfo().getName();
        companions.announce(serverName, request.payload());
        if (request.serverId() != null) {
            companions.recordServerId(serverName, request.serverId());
        }
        return new Response(request.operation(), request.requestId(), request.uuid(), true,
                "", LanguageChannel.ERROR_NONE, null, serverName, null);
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
        String language = LanguageRegistry.normalise(request.payload());
        if (language.isEmpty() || !registry.isKnown(language)) {
            return request.failure(LanguageChannel.ERROR_UNKNOWN_LANGUAGE);
        }
        return store(request, playerId, language, true);
    }

    private Response clearLanguage(Request request, UUID playerId) {
        // "Cleared" means back to the configured default, so a player with no
        // row is already clear and none is created for them.
        return store(request, playerId, registry.defaultLanguage(), false);
    }

    /**
     * Answers with everything the companion plugin's selector needs, in the
     * player's own language, so the companion ships no translations of its own.
     */
    private Response menu(Request request, UUID playerId) {
        Response current = getLanguage(request, playerId);
        // An unknown player simply has not chosen yet: show them the default
        // rather than refusing to open the menu.
        String language = current.success() ? current.language() : registry.defaultLanguage();

        Map<String, String> entries = new LinkedHashMap<>();
        for (String code : registry.languages()) {
            entries.put(code, registry.template(code, MessageKey.LANGUAGE_NAME));
        }

        return new Response(request.operation(), request.requestId(), request.uuid(), true,
                language, LanguageChannel.ERROR_NONE,
                new Menu(registry.template(language, MessageKey.MENU_TITLE),
                        registry.template(language, MessageKey.MENU_SELECT_HINT),
                        registry.template(language, MessageKey.MENU_CURRENT_HINT),
                        entries), null, null);
    }

    /**
     * Answers with everything the companion's {@code /settings} menu and its
     * first-join Discord chat question need, in the player's own language.
     */
    private Response settings(Request request, UUID playerId) {
        Response current = getLanguage(request, playerId);
        String language = current.success() ? current.language() : registry.defaultLanguage();
        boolean shown;
        try {
            shown = discordChatOf(playerId);
        } catch (SQLException e) {
            logger.error("{} failed for {}.", request.operation(), playerId, e);
            return request.failure(LanguageChannel.ERROR_DATABASE_ERROR);
        }

        return new Response(request.operation(), request.requestId(), request.uuid(), true,
                language, LanguageChannel.ERROR_NONE, null, null,
                new Settings(registry.template(language, MessageKey.SETTINGS_TITLE),
                        registry.template(language, MessageKey.SETTINGS_LANGUAGE),
                        registry.template(language, MessageKey.SETTINGS_LANGUAGE_HINT),
                        registry.template(language, MessageKey.LANGUAGE_NAME),
                        shown,
                        registry.template(language, MessageKey.SETTINGS_DISCORD_CHAT),
                        registry.template(language, MessageKey.SETTINGS_DISCORD_CHAT_ON),
                        registry.template(language, MessageKey.SETTINGS_DISCORD_CHAT_OFF),
                        registry.template(language, MessageKey.DISCORD_CHAT_PROMPT_TITLE),
                        registry.template(language, MessageKey.DISCORD_CHAT_PROMPT_YES),
                        registry.template(language, MessageKey.DISCORD_CHAT_PROMPT_NO)));
    }

    /**
     * Whether {@code playerId} sees Discord chat: from the cache when warm,
     * otherwise from their row. Hidden for a player with no row, or while the
     * database is down, since that is where everyone starts.
     */
    private boolean discordChatOf(UUID playerId) throws SQLException {
        Optional<Boolean> cached = discordChat.get(playerId);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (!database.isAvailable()) {
            return false;
        }
        boolean shown = players.find(playerId).map(Player::discordChat).orElse(false);
        discordChat.put(playerId, shown);
        if (proxy.getPlayer(playerId).isEmpty()) {
            discordChat.expireLater(playerId);
        }
        return shown;
    }

    private Response setDiscordChat(Request request, UUID playerId) {
        Boolean shown = switch (request.payload()) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
        if (shown == null) {
            return request.failure(LanguageChannel.ERROR_MALFORMED_REQUEST);
        }
        if (!database.isAvailable()) {
            return request.failure(LanguageChannel.ERROR_DATABASE_UNAVAILABLE);
        }
        String language = cache.get(playerId).orElse(registry.defaultLanguage());
        try {
            if (!players.updateDiscordChat(playerId, shown)) {
                // Someone with no row yet: give them one, then the setting.
                players.createIfAbsent(playerId, language);
                players.updateDiscordChat(playerId, shown);
            }
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("{} failed for {}.", request.operation(), playerId, e);
            return request.failure(LanguageChannel.ERROR_DATABASE_ERROR);
        }

        discordChat.put(playerId, shown);
        Optional<com.velocitypowered.api.proxy.Player> online = proxy.getPlayer(playerId);
        if (online.isEmpty()) {
            discordChat.expireLater(playerId);
        }
        online.ifPresent(player -> player.sendMessage(messages.prefixed(language,
                shown ? MessageKey.DISCORD_CHAT_SHOWN : MessageKey.DISCORD_CHAT_HIDDEN)));
        return request.success(language);
    }

    private Response store(Request request, UUID playerId, String language, boolean createIfMissing) {
        if (!database.isAvailable()) {
            return request.failure(LanguageChannel.ERROR_DATABASE_UNAVAILABLE);
        }
        try {
            if (!players.updateLanguage(playerId, language) && createIfMissing) {
                // Setting a language for someone with no row yet, e.g. a player
                // who has not joined since the plugin was installed.
                players.createIfAbsent(playerId, language);
            }
            cacheFor(playerId, language);
            confirmToPlayer(playerId, language);
            return request.success(language);
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("{} failed for {}.", request.operation(), playerId, e);
            return request.failure(LanguageChannel.ERROR_DATABASE_ERROR);
        }
    }

    /**
     * Tells the player their language changed, in the new language. Doing this
     * here rather than in the companion keeps every translated string on the
     * proxy, next to the language files.
     */
    private void confirmToPlayer(UUID playerId, String language) {
        proxy.getPlayer(playerId).ifPresent(player -> player.sendMessage(
                messages.prefixed(language, MessageKey.LANGUAGE_CHANGED,
                        Placeholder.unparsed("language", language))));
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

    // --- wire format -------------------------------------------------------

    private Request decode(byte[] data) {
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(data);
            ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
            String operation = in.readUTF();
            String requestId = in.readUTF();
            String uuid = in.readUTF();
            String payload = carriesPayload(operation) ? in.readUTF() : "";
            // Optional, so companions from before server ids were announced still work.
            String serverId = LanguageChannel.OP_ANNOUNCE.equals(operation) && bytes.available() > 0
                    ? in.readUTF() : null;
            return new Request(operation, requestId, uuid, payload, serverId);
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            // Guava wraps a truncated buffer in an unchecked exception.
            return null;
        }
    }

    /** Operations whose request carries a fourth field. */
    private static boolean carriesPayload(String operation) {
        return LanguageChannel.OP_SET.equals(operation) || LanguageChannel.OP_ANNOUNCE.equals(operation)
                || LanguageChannel.OP_SET_DISCORD_CHAT.equals(operation);
    }

    private void respond(ServerConnection source, Response response) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(response.operation());
        out.writeUTF(response.requestId());
        out.writeUTF(response.uuid());
        out.writeBoolean(response.success());
        out.writeUTF(response.language());
        out.writeUTF(response.error());

        if (response.serverName() != null) {
            out.writeUTF(response.serverName());
        }

        Menu menu = response.menu();
        if (menu != null) {
            out.writeUTF(menu.title());
            out.writeUTF(menu.selectHint());
            out.writeUTF(menu.currentHint());
            out.writeInt(menu.entries().size());
            for (Map.Entry<String, String> entry : menu.entries().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        }

        Settings settings = response.settings();
        if (settings != null) {
            out.writeUTF(settings.title());
            out.writeUTF(settings.languageLabel());
            out.writeUTF(settings.languageHint());
            out.writeUTF(settings.languageName());
            out.writeBoolean(settings.discordChat());
            out.writeUTF(settings.discordChatLabel());
            out.writeUTF(settings.discordChatOnHint());
            out.writeUTF(settings.discordChatOffHint());
            out.writeUTF(settings.promptTitle());
            out.writeUTF(settings.promptYes());
            out.writeUTF(settings.promptNo());
        }

        if (!source.sendPluginMessage(LanguageChannel.IDENTIFIER, out.toByteArray())) {
            logger.warn("Could not answer {} on {}: the server connection is gone.",
                    source.getServerInfo().getName(), LanguageChannel.IDENTIFIER.getId());
        }
    }

    /**
     * A decoded request. {@code payload} is the optional fourth field, and
     * {@code serverId} the fifth an announcement may carry, or {@code null}.
     */
    private record Request(String operation, String requestId, String uuid, String payload, String serverId) {

        Response success(String language) {
            return new Response(operation, requestId, uuid, true, language,
                    LanguageChannel.ERROR_NONE, null, null, null);
        }

        Response failure(String error) {
            return Response.failed(operation, requestId, uuid, error);
        }
    }

    /** The selector payload, present only on a {@code get_language_menu} reply. */
    private record Menu(String title, String selectHint, String currentHint, Map<String, String> entries) {
    }

    /** The settings payload, present only on a {@code get_settings_menu} reply. */
    private record Settings(String title, String languageLabel, String languageHint, String languageName,
                            boolean discordChat, String discordChatLabel, String discordChatOnHint,
                            String discordChatOffHint, String promptTitle, String promptYes, String promptNo) {
    }

    /**
     * A response, echoing the request header so replies can be correlated.
     *
     * <p>{@code menu}, {@code serverName} and {@code settings} are the
     * per-operation extras, each appended after the six base fields and each
     * present for exactly one operation.</p>
     */
    private record Response(String operation, String requestId, String uuid,
                            boolean success, String language, String error,
                            Menu menu, String serverName, Settings settings) {

        static Response failed(String operation, String requestId, String uuid, String error) {
            return new Response(operation, requestId, uuid, false, "", error, null, null, null);
        }
    }
}
