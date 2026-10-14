package me.ganamaga.katharsis_neoforge.menu;

import me.ganamaga.katharsis_neoforge.protocol.ProxyResponse;

import java.util.Optional;

/**
 * What the {@code /settings} menu and the first-join Discord chat question
 * show, and where.
 *
 * <p>Pure layout, with no Minecraft types, so it can be tested without a server.
 * {@link SettingsMenu} turns it into a chest.</p>
 */
public final class SettingsMenuModel {

    /** Three chest rows, with the buttons on the middle one. */
    public static final int SIZE = 27;

    /** Left of centre: the language, or "yes" in the question. */
    public static final int LEFT_SLOT = 11;

    /** Right of centre: Discord chat, or "no" in the question. */
    public static final int RIGHT_SLOT = 15;

    /** A button in the settings menu. */
    public enum Button {
        LANGUAGE, DISCORD_CHAT
    }

    private final String language;
    private final ProxyResponse.Settings settings;
    private final boolean discordChat;

    private SettingsMenuModel(String language, ProxyResponse.Settings settings, boolean discordChat) {
        this.language = language;
        this.settings = settings;
        this.discordChat = discordChat;
    }

    /** Reads a {@code get_settings_menu} reply. */
    public static SettingsMenuModel from(ProxyResponse response) {
        ProxyResponse.Settings settings = response.requireSettings();
        return new SettingsMenuModel(response.language(), settings, settings.discordChat());
    }

    /** This model after Discord chat was shown or hidden. */
    public SettingsMenuModel withDiscordChat(boolean shown) {
        return new SettingsMenuModel(language, settings, shown);
    }

    /** The viewer's language code, e.g. {@code cs_CZ}. */
    public String language() {
        return language;
    }

    /** The translated strings, as MiniMessage templates. */
    public ProxyResponse.Settings strings() {
        return settings;
    }

    /** Whether Discord chat is shown to the viewer. */
    public boolean discordChat() {
        return discordChat;
    }

    /** Lore for the Discord chat button in its current state. */
    public String discordChatHint() {
        return discordChat ? settings.discordChatOnHint() : settings.discordChatOffHint();
    }

    /** The resource pack model for the current language's flag. */
    public Optional<String> flagModel() {
        return LanguageMenuModel.flagModel(language);
    }

    /** The settings button at {@code slot}, if one sits there. */
    public static Optional<Button> buttonAt(int slot) {
        return switch (slot) {
            case LEFT_SLOT -> Optional.of(Button.LANGUAGE);
            case RIGHT_SLOT -> Optional.of(Button.DISCORD_CHAT);
            default -> Optional.empty();
        };
    }

    /**
     * The answer to the Discord chat question at {@code slot}: {@code true}
     * to show it, {@code false} to keep it hidden, empty for any other slot.
     */
    public static Optional<Boolean> answerAt(int slot) {
        return switch (slot) {
            case LEFT_SLOT -> Optional.of(true);
            case RIGHT_SLOT -> Optional.of(false);
            default -> Optional.empty();
        };
    }
}
