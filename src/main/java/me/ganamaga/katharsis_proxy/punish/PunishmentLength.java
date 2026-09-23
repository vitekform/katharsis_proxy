package me.ganamaga.katharsis_proxy.punish;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long a punishment lasts: a positive {@link Duration}, or for good.
 *
 * @param duration the length; {@code null} for {@link #PERMANENT}
 */
public record PunishmentLength(Duration duration) {

    public static final PunishmentLength PERMANENT = new PunishmentLength(null);

    public PunishmentLength {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException("a punishment must last a positive time");
        }
    }

    public static PunishmentLength of(Duration duration) {
        return new PunishmentLength(Objects.requireNonNull(duration, "duration"));
    }

    public boolean isPermanent() {
        return duration == null;
    }

    /** When a punishment issued at {@code issuedAt} runs out; {@code null} if it never does. */
    public Instant expiresAt(Instant issuedAt) {
        return duration == null ? null : issuedAt.plus(duration);
    }
}
