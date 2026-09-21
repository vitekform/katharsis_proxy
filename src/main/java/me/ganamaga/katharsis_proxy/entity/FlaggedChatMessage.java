package me.ganamaga.katharsis_proxy.entity;

import java.util.Objects;

/**
 * A chat message the filters held back, kept in the log alongside the rest so
 * that what a player wrote and what was stopped read as one history.
 *
 * @param message the message itself, exactly as it was written
 * @param source  which layer stopped it, as {@code rules}, {@code openai} or
 *                {@code unavailable}
 * @param reason  what it objected to: the rule that matched, or the categories
 *                the model returned
 */
public record FlaggedChatMessage(ChatMessage message, String source, String reason) {

    /** Width of the {@code flag_source} column. */
    public static final int SOURCE_MAX_LENGTH = 16;

    /** Width of the {@code flag_reason} column. */
    public static final int REASON_MAX_LENGTH = 255;

    public FlaggedChatMessage {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        if (source.length() > SOURCE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "flag source '" + source + "' exceeds " + SOURCE_MAX_LENGTH + " characters");
        }
        // Unlike the source, the reason is free text from a rule or a model, so
        // it is cut to fit rather than rejected: losing the tail of an
        // explanation must never cost the log the message itself.
        if (reason.length() > REASON_MAX_LENGTH) {
            reason = reason.substring(0, REASON_MAX_LENGTH);
        }
    }
}
