package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A username claimed on the network, either with a password or by a Mojang
 * account.
 *
 * <p>Names are unique regardless of case, as they are to Mojang. The case the
 * name was claimed in is kept, because an offline-mode proxy derives the
 * player's uuid from the exact spelling: {@code Steve} and {@code steve} would
 * otherwise be one account with two sets of player data.</p>
 *
 * @param username     the name as it was claimed
 * @param playerUuid   the uuid the player had when they last logged in
 * @param passwordHash Argon2id hash in PHC format; {@code null} for a premium account
 * @param premium      whether Mojang vouches for this name, so it logs in without a password
 * @param registeredAt when the name was claimed
 * @param lastLoginAt  when the player last logged in, {@code null} if never since claiming it
 */
public record AuthAccount(String username, UUID playerUuid, String passwordHash, boolean premium,
                          Instant registeredAt, Instant lastLoginAt) {

    /** Minecraft caps names at 16 characters; offline clients can send a little more. */
    public static final int USERNAME_MAX_LENGTH = 32;

    public AuthAccount {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(registeredAt, "registeredAt");
        if (username.isEmpty() || username.length() > USERNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("username must be 1-" + USERNAME_MAX_LENGTH + " characters");
        }
        if (!premium && passwordHash == null) {
            throw new IllegalArgumentException("an account without a Mojang account needs a password");
        }
    }

    /** A name claimed with a password. */
    public static AuthAccount registered(String username, UUID playerUuid, String passwordHash, Instant now) {
        return new AuthAccount(username, playerUuid, Objects.requireNonNull(passwordHash, "passwordHash"),
                false, now, now);
    }

    /** A name Mojang logged in for us. */
    public static AuthAccount premium(String username, UUID playerUuid, Instant now) {
        return new AuthAccount(username, playerUuid, null, true, now, now);
    }

    /** The case-insensitive key the account is stored under. */
    public static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(username);
    }

    public Optional<String> password() {
        return Optional.ofNullable(passwordHash);
    }
}
