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
    LANGUAGE_CURRENT("language-current"),

    /** Placeholders: {@code <languages>}. */
    LANGUAGE_AVAILABLE("language-available"),

    /** Placeholders: {@code <language>}. */
    LANGUAGE_CHANGED("language-changed"),

    /** Placeholders: {@code <language>}. */
    LANGUAGE_UNKNOWN("language-unknown"),

    /** Placeholders: none. */
    LANGUAGE_FAILED("language-failed"),

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
    MENU_CURRENT_HINT("menu-current-hint");

    private final String key;

    MessageKey(String key) {
        this.key = key;
    }

    /** The key this message is stored under in the language files. */
    public String key() {
        return key;
    }
}
