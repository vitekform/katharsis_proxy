package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A chat message a player wrote on one of the backend servers.
 *
 * @param playerUuid the author's unique id
 * @param playerName the author's username when they wrote it
 * @param server     the backend the message was written on, as named in velocity.toml
 * @param content    the message as plain text
 * @param sentAt     when the proxy received the report
 */
public record ChatMessage(UUID playerUuid, String playerName, String server, String content, Instant sentAt) {

    /** Width of the {@code player_name} column. Leaves room for Bedrock prefixes. */
    public static final int PLAYER_NAME_MAX_LENGTH = 64;

    /** Width of the {@code server} column. */
    public static final int SERVER_MAX_LENGTH = 255;

    public ChatMessage {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sentAt, "sentAt");
        if (playerName.length() > PLAYER_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "player name '" + playerName + "' exceeds " + PLAYER_NAME_MAX_LENGTH + " characters");
        }
        if (server.length() > SERVER_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "server name '" + server + "' exceeds " + SERVER_MAX_LENGTH + " characters");
        }
    }
}
