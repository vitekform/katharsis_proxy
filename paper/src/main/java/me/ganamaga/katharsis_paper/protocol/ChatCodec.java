package me.ganamaga.katharsis_paper.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Encodes chat reports for the proxy.
 *
 * <p>Deliberately free of Bukkit types so the wire format can be tested without
 * a running server.</p>
 */
public final class ChatCodec {

    /** HTML escaping off, so {@code <} reaches the proxy as itself rather than {@code <}. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ChatCodec() {
    }

    /** Builds a {@code player_msg} report as UTF-8 JSON. */
    public static byte[] encodePlayerMessage(UUID player, String playerName, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("action", ChatChannel.ACTION_PLAYER_MSG);
        json.addProperty("player", player.toString());
        json.addProperty("player_name", playerName);
        json.addProperty("content", content);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }
}
