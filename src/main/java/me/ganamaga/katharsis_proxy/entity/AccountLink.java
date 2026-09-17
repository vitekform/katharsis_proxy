package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Minecraft account permanently linked to a Discord account. Each side can
 * be linked only once.
 *
 * @param playerUuid      the Minecraft account
 * @param playerName      the player's username when they linked
 * @param discordUserId   the Discord account's user id
 * @param discordUsername the Discord username when the link code was made
 * @param linkedAt        when the link was made
 */
public record AccountLink(UUID playerUuid, String playerName, long discordUserId, String discordUsername,
                          Instant linkedAt) {

    /** Width of the {@code player_name} column, as in the chat log. */
    public static final int PLAYER_NAME_MAX_LENGTH = ChatMessage.PLAYER_NAME_MAX_LENGTH;

    /** Width of the {@code discord_username} column. Discord allows 32. */
    public static final int DISCORD_USERNAME_MAX_LENGTH = 64;

    public AccountLink {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(discordUsername, "discordUsername");
        Objects.requireNonNull(linkedAt, "linkedAt");
        if (playerName.length() > PLAYER_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "player name '" + playerName + "' exceeds " + PLAYER_NAME_MAX_LENGTH + " characters");
        }
        if (discordUsername.length() > DISCORD_USERNAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Discord username '" + discordUsername + "' exceeds " + DISCORD_USERNAME_MAX_LENGTH + " characters");
        }
    }
}
