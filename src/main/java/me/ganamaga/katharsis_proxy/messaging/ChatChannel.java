package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * Wire constants for the {@code katharsis:chat} plugin message channel, on
 * which backend servers report what players write in chat.
 *
 * <p>Unlike {@link LanguageChannel} this is one-way and never answered. Each
 * message is a single UTF-8 JSON object; the format is documented in
 * {@code docs/chat-messaging.md}.</p>
 */
public final class ChatChannel {

    /** The channel backends send chat reports on. */
    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create("katharsis", "chat");

    /** The {@code action} of a report that a player wrote a chat message. */
    public static final String ACTION_PLAYER_MSG = "player_msg";

    /** Field naming what the message reports. */
    public static final String FIELD_ACTION = "action";

    /**
     * Optional field naming the sending server, as set in the companion's
     * config. When it is absent or blank the server's velocity.toml name is
     * used instead.
     */
    public static final String FIELD_SERVER = "server";

    /** Field holding the player's uuid, canonical dashed form. */
    public static final String FIELD_PLAYER = "player";

    /** Field holding the player's username. */
    public static final String FIELD_PLAYER_NAME = "player_name";

    /** Field holding the chat message as plain text. */
    public static final String FIELD_CONTENT = "content";

    private ChatChannel() {
    }
}
