package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * Wire constants for the {@code katharsis:language} plugin message channel,
 * which lets backend servers read and change a player's language.
 *
 * <p>The protocol is documented in {@code docs/plugin-messaging.md}. Both
 * requests and responses travel on this one channel; every field is written
 * with {@code DataOutput#writeUTF} except {@code success}, which is a
 * {@code boolean}.</p>
 */
public final class LanguageChannel {

    /** The channel both requests and responses use. */
    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create("katharsis", "language");

    /** Reads a player's language. Request: none beyond the header. */
    public static final String OP_GET = "get_language";

    /** Sets a player's language. Request carries the language code. */
    public static final String OP_SET = "set_language";

    /** Resets a player's language to the configured default. */
    public static final String OP_CLEAR = "clear_language";

    /**
     * Tells the proxy this backend runs the companion plugin and owns the
     * language selector. Request carries the companion's version in the
     * fourth field, and may carry its server id in an optional fifth.
     */
    public static final String OP_ANNOUNCE = "announce";

    /**
     * Everything the companion plugin needs to draw its selector: the player's
     * current language, the menu's strings, and every available language with
     * its own name. Response carries the extra fields documented in
     * {@code docs/plugin-messaging.md}.
     */
    public static final String OP_MENU = "get_language_menu";

    /**
     * Sent by the proxy, unasked, to have the companion open the selector for
     * a player new to the network. Laid out as a successful response with an
     * empty request id.
     */
    public static final String OP_OPEN_MENU = "open_language_menu";

    /**
     * Everything the companion plugin needs to draw its {@code /settings}
     * menu, and the Discord chat question a new player is asked, in the
     * player's language. Response carries the extra fields documented in
     * {@code docs/plugin-messaging.md}.
     */
    public static final String OP_SETTINGS = "get_settings_menu";

    /**
     * Shows or hides the Discord chat channels in game for a player. Request
     * carries {@code true} or {@code false}.
     */
    public static final String OP_SET_DISCORD_CHAT = "set_discord_chat";

    /** Sent as the error field of a successful response. */
    public static final String ERROR_NONE = "";

    /** The request could not be decoded, or a field was missing. */
    public static final String ERROR_MALFORMED_REQUEST = "malformed_request";

    /** The operation is not one of those above. */
    public static final String ERROR_UNKNOWN_OPERATION = "unknown_operation";

    /** The uuid field was not a canonical UUID. */
    public static final String ERROR_INVALID_UUID = "invalid_uuid";

    /** The requested language has no file in {@code lang/}. */
    public static final String ERROR_UNKNOWN_LANGUAGE = "unknown_language";

    /** No row exists for that player, i.e. they have never joined. */
    public static final String ERROR_UNKNOWN_PLAYER = "unknown_player";

    /** The database is not reachable right now. */
    public static final String ERROR_DATABASE_UNAVAILABLE = "database_unavailable";

    /** The database rejected the query; the proxy log has the details. */
    public static final String ERROR_DATABASE_ERROR = "database_error";

    private LanguageChannel() {
    }
}
