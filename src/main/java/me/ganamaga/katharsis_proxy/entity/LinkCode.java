package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * A one-time code, issued on Discord, that links whoever types it in game to
 * the Discord account that asked for it.
 *
 * @param code            the code itself, normalised to upper case
 * @param discordUserId   the Discord account it links to
 * @param discordUsername that account's username when the code was made
 * @param expiresAt       when the code stops working
 */
public record LinkCode(String code, long discordUserId, String discordUsername, Instant expiresAt) {

    public LinkCode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(discordUsername, "discordUsername");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (discordUsername.length() > AccountLink.DISCORD_USERNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Discord username '" + discordUsername + "' exceeds "
                    + AccountLink.DISCORD_USERNAME_MAX_LENGTH + " characters");
        }
    }
}
