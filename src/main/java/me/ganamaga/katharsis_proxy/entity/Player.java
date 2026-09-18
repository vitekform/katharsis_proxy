package me.ganamaga.katharsis_proxy.entity;

import java.util.Objects;
import java.util.UUID;

/**
 * A player known to the network, identified by their Mojang UUID.
 *
 * @param uuid        the player's unique id, the primary key of the row
 * @param language    the player's language tag, e.g. {@code en_US}
 * @param discordChat whether messages from the Discord chat channels are shown
 *                    to them in game; off until they turn it on
 */
public record Player(UUID uuid, String language, boolean discordChat) {

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

    /** A player with Discord chat hidden, as every player starts. */
    public Player(UUID uuid, String language) {
        this(uuid, language, false);
    }

    /** Returns a copy of this player with a different language. */
    public Player withLanguage(String newLanguage) {
        return new Player(uuid, newLanguage, discordChat);
    }
}
