package me.ganamaga.katharsis_proxy.moderation;

import me.ganamaga.katharsis_proxy.entity.ChatMessage;

/**
 * Somewhere flagged chat is reported to, besides the log and the database.
 */
@FunctionalInterface
public interface ModerationAlerts {

    /** Does nothing, for when there is nobody to tell. */
    ModerationAlerts NONE = (message, verdict) -> {
    };

    /**
     * Reports that {@code message} was held back. Must not block: it is called
     * while a chat message is being handled.
     */
    void flagged(ChatMessage message, ModerationVerdict verdict);

    /** Reports to {@code first}, then {@code second}. */
    static ModerationAlerts both(ModerationAlerts first, ModerationAlerts second) {
        return (message, verdict) -> {
            first.flagged(message, verdict);
            second.flagged(message, verdict);
        };
    }
}
