package me.ganamaga.katharsis_paper.protocol;

/**
 * Wire constants for the {@code katharsis:chat} channel.
 *
 * <p>This mirrors the proxy plugin's class of the same name, for the same
 * reason {@link LanguageChannel} does; {@code docs/chat-messaging.md} in the
 * proxy repository is the contract both sides implement.</p>
 */
public final class ChatChannel {

    /** The channel chat reports travel on. The proxy never answers. */
    public static final String CHANNEL = "katharsis:chat";

    /** Reports that a player wrote a chat message. */
    public static final String ACTION_PLAYER_MSG = "player_msg";

    private ChatChannel() {
    }
}
