package me.ganamaga.katharsis_proxy.entity;

import java.util.Objects;
import java.util.UUID;

/**
 * A player known to the network, identified by their Mojang UUID.
 *
 * @param uuid     the player's unique id, the primary key of the row
 * @param language the player's language tag, e.g. {@code en_US}
 */
public record Player(UUID uuid, String language) {

    /** Width of the {@code language} column; {@code en_US} is the longest shape we store. */
    public static final int LANGUAGE_MAX_LENGTH = 5;

    public Player {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(language, "language");
        if (language.length() > LANGUAGE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "language '" + language + "' exceeds " + LANGUAGE_MAX_LENGTH + " characters");
        }
    }

    /** Returns a copy of this player with a different language. */
    public Player withLanguage(String newLanguage) {
        return new Player(uuid, newLanguage);
    }
}
