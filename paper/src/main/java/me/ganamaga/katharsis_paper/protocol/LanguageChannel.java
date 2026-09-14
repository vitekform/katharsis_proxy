package me.ganamaga.katharsis_paper.protocol;

/**
 * Wire constants for the {@code katharsis:language} channel.
 *
 * <p>This mirrors the proxy plugin's class of the same name. The two are
 * separate artifacts with no shared dependency, so the constants are repeated
 * rather than shared; {@code docs/plugin-messaging.md} in the proxy repository
 * is the contract both sides implement.</p>
 */
public final class LanguageChannel {

    /** The channel both requests and responses travel on. */
    public static final String CHANNEL = "katharsis:language";

    /** Tells the proxy this server owns the language selector. */
    public static final String OP_ANNOUNCE = "announce";

    /** Reads a player's language. */
    public static final String OP_GET = "get_language";

    /** Stores a new language for a player. */
    public static final String OP_SET = "set_language";

    /** Everything needed to draw the selector, in the player's language. */
    public static final String OP_MENU = "get_language_menu";

    /** The error field of a successful response. */
    public static final String ERROR_NONE = "";

    private LanguageChannel() {
    }
}
