package me.ganamaga.katharsis_proxy.messaging;

import me.ganamaga.katharsis_proxy.entity.ChatMessage;

/**
 * Somewhere reported chat is passed on to, besides the database.
 */
@FunctionalInterface
public interface ChatRelay {

    /** Does nothing, for when chat goes nowhere else. */
    ChatRelay NONE = (serverId, message) -> {
    };

    /**
     * Passes {@code message} on. Called on the event thread, so it must not
     * block.
     *
     * @param serverId the id the sending server reported, or its velocity.toml
     *                 name when it reported none
     */
    void relay(String serverId, ChatMessage message);
}
