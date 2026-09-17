package me.ganamaga.katharsis_proxy.i18n;

/**
 * Every translatable message, paired with its key in the language files.
 *
 * <p>Adding a constant here makes {@link LanguageRegistry} report the key as
 * missing from any language file that has not been updated, so translations
 * cannot silently fall behind the code.</p>
 */
public enum MessageKey {

    /** Prepended to every other message. No placeholders. */
    PREFIX("prefix"),

    /** Placeholders: none. */
    PLAYERS_ONLY("players-only"),

    /** Placeholders: {@code <server>}. */
    CONNECTING("connecting"),

    /** Placeholders: {@code <server>}. */
    ALREADY_CONNECTED("already-connected"),

    /** Placeholders: {@code <server>}. */
    UNKNOWN_SERVER("unknown-server"),

    /** Placeholders: {@code <server>}. */
    CONNECTION_FAILED("connection-failed"),

    /** Placeholders: {@code <time>}. */
    ON_COOLDOWN("on-cooldown"),

    /** Placeholders: {@code <minutes>}, {@code <seconds>}. */
    DURATION_MINUTES_SECONDS("duration-minutes-seconds"),

    /** Placeholders: {@code <seconds>}. */
    DURATION_SECONDS("duration-seconds"),

    /** Placeholders: {@code <language>}. */
    LANGUAGE_CHANGED("language-changed"),

    /**
     * This language's own name, as shown in the companion plugin's selector.
     * Each language file names itself, so the menu reads natively in every
     * entry rather than listing everything in one language.
     * Placeholders: none.
     */
    LANGUAGE_NAME("language-name"),

    /** Title of the companion plugin's selector. Placeholders: none. */
    MENU_TITLE("menu-title"),

    /** Lore on a language the player may pick. Placeholders: none. */
    MENU_SELECT_HINT("menu-select-hint"),

    /** Lore on the language the player already uses. Placeholders: none. */
    MENU_CURRENT_HINT("menu-current-hint"),

    /** In game, /link without a code. Placeholders: none. */
    LINK_USAGE("link-usage"),

    /** In game, the accounts are now linked. Placeholders: {@code <discord>}. */
    LINK_SUCCESS("link-success"),

    /** In game, the code is unknown, expired or used. Placeholders: none. */
    LINK_INVALID_CODE("link-invalid-code"),

    /** In game, the player is already linked. Placeholders: {@code <discord>}. */
    LINK_ALREADY_LINKED("link-already-linked"),

    /** In game, the code's Discord account is linked to someone else. Placeholders: none. */
    LINK_DISCORD_TAKEN("link-discord-taken"),

    /** In game, linking cannot be done right now. Placeholders: none. */
    LINK_UNAVAILABLE("link-unavailable"),

    /**
     * On Discord, the description of the /link command. Plain text, at most 100
     * characters. Placeholders: none.
     */
    DISCORD_LINK_DESCRIPTION("discord-link-description"),

    /**
     * On Discord, the reply carrying a new code. Discord markdown, not
     * MiniMessage colours. Placeholders: {@code <code>}, {@code <hours>}.
     */
    DISCORD_LINK_CODE("discord-link-code"),

    /** On Discord, the account is already linked. Placeholders: {@code <player>}. */
    DISCORD_LINK_ALREADY_LINKED("discord-link-already-linked"),

    /** On Discord, linking cannot be done right now. Placeholders: none. */
    DISCORD_LINK_UNAVAILABLE("discord-link-unavailable");

    private final String key;

    MessageKey(String key) {
        this.key = key;
    }

    /** The key this message is stored under in the language files. */
    public String key() {
        return key;
    }
}
