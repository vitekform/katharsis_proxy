package me.ganamaga.katharsis_proxy.moderation;

import java.util.UUID;

/**
 * Tells the author of a held-back message that it did not go through, so chat
 * does not simply swallow what they wrote.
 */
@FunctionalInterface
public interface ModerationNotice {

    /** Says nothing, for when the warning is switched off. */
    ModerationNotice NONE = (playerUuid, verdict) -> {
    };

    /**
     * Warns the player, if they are still connected. Must not block: it is
     * called while a chat message is being handled.
     */
    void held(UUID playerUuid, ModerationVerdict verdict);
}
