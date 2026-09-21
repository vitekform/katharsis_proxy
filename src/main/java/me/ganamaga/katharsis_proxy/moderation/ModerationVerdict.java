package me.ganamaga.katharsis_proxy.moderation;

import java.util.Objects;

/**
 * What the filters made of one message.
 *
 * @param source what stopped it, or {@code null} when nothing did
 * @param reason why, short enough for a log line and a database column: the
 *               rule that matched, or the categories the model returned
 */
public record ModerationVerdict(ModerationSource source, String reason) {

    /** Longest reason stored; anything longer is cut to fit its column. */
    public static final int REASON_MAX_LENGTH = 255;

    private static final ModerationVerdict CLEAN = new ModerationVerdict(null, "");

    public ModerationVerdict {
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > REASON_MAX_LENGTH) {
            reason = reason.substring(0, REASON_MAX_LENGTH);
        }
    }

    /** Nothing objected; the message may be relayed and logged as usual. */
    public static ModerationVerdict clean() {
        return CLEAN;
    }

    public static ModerationVerdict flagged(ModerationSource source, String reason) {
        return new ModerationVerdict(Objects.requireNonNull(source, "source"), reason);
    }

    public boolean isFlagged() {
        return source != null;
    }
}
