package me.ganamaga.katharsis_proxy.entity;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Who issued or revoked a {@link Punishment}.
 *
 * @param uuid   the staff member's player uuid; {@code null} for the console and
 *               for punishments the plugin issues on its own
 * @param name   what to show as the issuer
 * @param source where the command came from
 */
public record IssuerRef(UUID uuid, String name, Source source) {

    /** Width of the issuer name columns. */
    public static final int NAME_MAX_LENGTH = 64;

    public enum Source {
        /** A staff member in game. */
        GAME,
        /** The proxy console. */
        CONSOLE,
        /** A staff member through a Discord slash command. */
        DISCORD,
        /** Chat moderation flagged a message. */
        AUTOMOD,
        /** Enough warnings piled up. */
        ESCALATION,
        /** An account joined from the address of a banned one. */
        EVASION;

        /** The form stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Source fromKey(String key) {
            return valueOf(key.toUpperCase(Locale.ROOT));
        }
    }

    public IssuerRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("issuer name must be 1-" + NAME_MAX_LENGTH + " characters");
        }
    }

    public static IssuerRef console() {
        return new IssuerRef(null, "Console", Source.CONSOLE);
    }

    /** Whether a person, rather than the plugin itself, is behind this. */
    public boolean isPerson() {
        return source == Source.GAME || source == Source.CONSOLE || source == Source.DISCORD;
    }
}
