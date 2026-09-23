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
    DISCORD_LINK_UNAVAILABLE("discord-link-unavailable"),

    /**
     * In game, a message written in a Discord chat channel. Placeholders:
     * {@code <player>}, the author's linked Minecraft name, and
     * {@code <message>}.
     */
    DISCORD_CHAT_MESSAGE("discord-chat-message"),

    /** Title of the companion plugin's /settings menu. Placeholders: none. */
    SETTINGS_TITLE("settings-title"),

    /** Name of the settings item that opens the language selector. Placeholders: none. */
    SETTINGS_LANGUAGE("settings-language"),

    /**
     * Lore on the language item. Placeholders: {@code <language>}, the
     * current language's own name, filled in by the companion.
     */
    SETTINGS_LANGUAGE_HINT("settings-language-hint"),

    /** Name of the settings item that shows or hides Discord chat. Placeholders: none. */
    SETTINGS_DISCORD_CHAT("settings-discord-chat"),

    /** Lore on the Discord chat item while it is shown. Placeholders: none. */
    SETTINGS_DISCORD_CHAT_ON("settings-discord-chat-on"),

    /** Lore on the Discord chat item while it is hidden. Placeholders: none. */
    SETTINGS_DISCORD_CHAT_OFF("settings-discord-chat-off"),

    /**
     * Title of the question a new player is asked after picking a language:
     * should Discord chat be shown in game? Placeholders: none.
     */
    DISCORD_CHAT_PROMPT_TITLE("discord-chat-prompt-title"),

    /** The answer that shows Discord chat. Placeholders: none. */
    DISCORD_CHAT_PROMPT_YES("discord-chat-prompt-yes"),

    /** The answer that keeps Discord chat hidden. Placeholders: none. */
    DISCORD_CHAT_PROMPT_NO("discord-chat-prompt-no"),

    /** In game, Discord chat is now shown. Placeholders: none. */
    DISCORD_CHAT_SHOWN("discord-chat-shown"),

    /** In game, Discord chat is now hidden. Placeholders: none. */
    DISCORD_CHAT_HIDDEN("discord-chat-hidden"),

    /**
     * In game, the moderation filters held the player's message back, so it
     * reached neither the chat log nor Discord. Placeholders: none, on purpose:
     * repeating what was written, or naming the rule that caught it, only helps
     * the author find a spelling that gets through.
     */
    CHAT_BLOCKED("chat-blocked"),

    /**
     * In game, the message could not be checked at all, so it was held back
     * rather than passed on unchecked. Says to try again, since nothing is
     * wrong with the message itself. Placeholders: none.
     */
    CHAT_UNCHECKED("chat-unchecked"),

    /** Big title over the login void. Placeholders: none. */
    AUTH_TITLE("auth-title"),

    /** Subtitle under it for a player with a password. Placeholders: none. */
    AUTH_SUBTITLE_LOGIN("auth-subtitle-login"),

    /** Subtitle under it for a player who has yet to choose one. Placeholders: none. */
    AUTH_SUBTITLE_REGISTER("auth-subtitle-register"),

    /** In the login void, how to log in. Placeholders: none. */
    AUTH_PROMPT_LOGIN("auth-prompt-login"),

    /** In the login void, how to register. Placeholders: none. */
    AUTH_PROMPT_REGISTER("auth-prompt-register"),

    /** Anything but /login or /register before logging in. Placeholders: none. */
    AUTH_ONLY_AUTH_COMMANDS("auth-only-auth-commands"),

    /** /login without exactly one argument. Placeholders: none. */
    AUTH_LOGIN_USAGE("auth-login-usage"),

    /** /register without exactly two arguments. Placeholders: none. */
    AUTH_REGISTER_USAGE("auth-register-usage"),

    /** /login on a name nobody registered. Placeholders: none. */
    AUTH_NOT_REGISTERED("auth-not-registered"),

    /** /register on a name already registered. Placeholders: none. */
    AUTH_ALREADY_REGISTERED("auth-already-registered"),

    /** Placeholders: {@code <attempts>}, how many are left before the kick. */
    AUTH_WRONG_PASSWORD("auth-wrong-password"),

    /** Placeholders: {@code <min>}. */
    AUTH_PASSWORD_TOO_SHORT("auth-password-too-short"),

    /** Placeholders: {@code <max>}. */
    AUTH_PASSWORD_TOO_LONG("auth-password-too-long"),

    /** The two passwords given to /register differ. Placeholders: none. */
    AUTH_PASSWORD_MISMATCH("auth-password-mismatch"),

    /** The password is the player's name. Placeholders: none. */
    AUTH_PASSWORD_IS_USERNAME("auth-password-is-username"),

    /** A command arrived while the previous one is still being checked. Placeholders: none. */
    AUTH_BUSY("auth-busy"),

    /** Placeholders: none. */
    AUTH_LOGGED_IN("auth-logged-in"),

    /** Placeholders: none. */
    AUTH_REGISTERED("auth-registered"),

    /** /login or /register once already logged in. Placeholders: none. */
    AUTH_ALREADY_LOGGED_IN("auth-already-logged-in"),

    /** Kick reason: too many wrong passwords. Placeholders: none. */
    AUTH_KICK_ATTEMPTS("auth-kick-attempts"),

    /** Kick reason: did not log in in time. Placeholders: none. */
    AUTH_KICK_TIMEOUT("auth-kick-timeout"),

    /**
     * Kick reason: the name is registered in another case. Placeholders:
     * {@code <name>}, the registered spelling.
     */
    AUTH_KICK_WRONG_CASE("auth-kick-wrong-case"),

    /** Kick reason, or reply: accounts cannot be checked right now. Placeholders: none. */
    AUTH_UNAVAILABLE("auth-unavailable"),

    /** Placeholders: {@code <days>}, {@code <hours>}. */
    DURATION_DAYS_HOURS("duration-days-hours"),

    /** Placeholders: {@code <hours>}, {@code <minutes>}. */
    DURATION_HOURS_MINUTES("duration-hours-minutes"),

    /** A punishment that never runs out. Placeholders: none. */
    DURATION_PERMANENT("duration-permanent"),

    /** Placeholders: none. */
    PUNISH_TYPE_BAN("punish-type-ban"),

    /** Placeholders: none. */
    PUNISH_TYPE_MUTE("punish-type-mute"),

    /** Placeholders: none. */
    PUNISH_TYPE_WARN("punish-type-warn"),

    /** Placeholders: none. */
    PUNISH_TYPE_KICK("punish-type-kick"),

    /** The whole network, as a scope. Placeholders: none. */
    PUNISH_SCOPE_GLOBAL("punish-scope-global"),

    /** Placeholders: {@code <name>}. */
    PUNISH_SCOPE_SERVER("punish-scope-server"),

    /** Placeholders: {@code <name>}. */
    PUNISH_SCOPE_GROUP("punish-scope-group"),

    /** Disconnect screen of a network-wide ban. Placeholders: {@code <reason>}, {@code <expires>} (a duration, or permanent), {@code <issuer>}, {@code <id>}, {@code <appeal>} (the appeal line, or nothing). */
    PUNISH_BAN_SCREEN("punish-ban-screen"),

    /** Added to the ban screen when an appeal URL is set. Placeholders: {@code <url>}. */
    PUNISH_APPEAL_LINE("punish-appeal-line"),

    /** Disconnect screen of a kick. Placeholders: {@code <reason>}, {@code <issuer>}. */
    PUNISH_KICK_SCREEN("punish-kick-screen"),

    /** Disconnect screen when bans cannot be checked and fail-closed is on. Placeholders: none. */
    PUNISH_UNAVAILABLE_SCREEN("punish-unavailable-screen"),

    /** Placeholders: {@code <usage>}, the command's syntax. */
    PUNISH_USAGE("punish-usage"),

    /** Placeholders: {@code <player>}, {@code <type>}, {@code <scope>}, {@code <duration>}, {@code <id>}. */
    PUNISH_ISSUED("punish-issued"),

    /** Placeholders: {@code <player>}, {@code <id>}. */
    PUNISH_KICKED("punish-kicked"),

    /** No address to ban along with the account. Placeholders: {@code <player>}. */
    PUNISH_ADDRESS_SKIPPED("punish-address-skipped"),

    /** Placeholders: {@code <player>}, {@code <type>}, {@code <count>}. */
    PUNISH_REVOKED("punish-revoked"),

    /** Placeholders: {@code <player>}, {@code <type>}, {@code <id>}. */
    PUNISH_ALREADY_ACTIVE("punish-already-active"),

    /** Placeholders: {@code <player>}. */
    PUNISH_TARGET_EXEMPT("punish-target-exempt"),

    /** Placeholders: {@code <player>}. */
    PUNISH_TARGET_OUTRANKS("punish-target-outranks"),

    /** Placeholders: {@code <player>}. */
    PUNISH_UNKNOWN_PLAYER("punish-unknown-player"),

    /** Placeholders: {@code <player>}. */
    PUNISH_NOT_ONLINE("punish-not-online"),

    /** An address-only punishment with no address to use. Placeholders: {@code <player>}. */
    PUNISH_NO_ADDRESS("punish-no-address"),

    /** Placeholders: {@code <player>}, {@code <type>}. */
    PUNISH_NOTHING_ACTIVE("punish-nothing-active"),

    /** Placeholders: {@code <id>}, {@code <type>}. */
    PUNISH_UNKNOWN_ID("punish-unknown-id"),

    /** Placeholders: {@code <input>}. */
    PUNISH_INVALID_DURATION("punish-invalid-duration"),

    /** Placeholders: {@code <scope>}. */
    PUNISH_UNKNOWN_SCOPE("punish-unknown-scope"),

    /** Placeholders: none. */
    PUNISH_NO_GLOBAL_PERMISSION("punish-no-global-permission"),

    /** Placeholders: none. */
    PUNISH_UNAVAILABLE("punish-unavailable"),

    /** To staff. Placeholders: {@code <issuer>}, {@code <player>}, {@code <type>}, {@code <scope>}, {@code <duration>}, {@code <reason>}, {@code <id>}. */
    PUNISH_STAFF_ISSUED("punish-staff-issued"),

    /** To staff. Placeholders: {@code <issuer>}, {@code <player>}, {@code <reason>}, {@code <id>}. */
    PUNISH_STAFF_KICKED("punish-staff-kicked"),

    /** To staff. Placeholders: {@code <issuer>}, {@code <player>}, {@code <type>}, {@code <id>}. */
    PUNISH_STAFF_REVOKED("punish-staff-revoked"),

    /** Placeholders: {@code <player>}, {@code <page>}, {@code <pages>}. */
    PUNISH_HISTORY_HEADER("punish-history-header"),

    /** Placeholders: {@code <id>}, {@code <date>}, {@code <type>}, {@code <scope>}, {@code <duration>}, {@code <reason>}, {@code <issuer>}, {@code <status>}. */
    PUNISH_HISTORY_LINE("punish-history-line"),

    /** Placeholders: {@code <player>}. */
    PUNISH_HISTORY_EMPTY("punish-history-empty"),

    /** Placeholders: none. */
    PUNISH_STATUS_ACTIVE("punish-status-active"),

    /** Placeholders: none. */
    PUNISH_STATUS_EXPIRED("punish-status-expired"),

    /** Placeholders: {@code <issuer>}. */
    PUNISH_STATUS_REVOKED("punish-status-revoked"),

    /** A kick, which has no state. Placeholders: none. */
    PUNISH_STATUS_DONE("punish-status-done"),

    /** Placeholders: {@code <player>}, {@code <uuid>}. */
    PUNISH_CHECK_HEADER("punish-check-header"),

    /** Placeholders: {@code <first>}, {@code <last>}, {@code <ip>}. */
    PUNISH_CHECK_SEEN("punish-check-seen"),

    /** Placeholders: {@code <type>}, {@code <scope>}, {@code <remaining>}, {@code <reason>}, {@code <id>}. */
    PUNISH_CHECK_ACTIVE("punish-check-active"),

    /** Placeholders: none. */
    PUNISH_CHECK_NONE("punish-check-none"),

    /** Stands in for an address the viewer may not see. Placeholders: none. */
    PUNISH_IP_HIDDEN("punish-ip-hidden");

    private final String key;

    MessageKey(String key) {
        this.key = key;
    }

    /** The key this message is stored under in the language files. */
    public String key() {
        return key;
    }
}
