package me.ganamaga.katharsis_paper.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatCodecTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    /**
     * {@code player_msg} from {@code Notch} saying {@code hi "there" <>}, as this
     * codec writes it. The proxy has a test that feeds exactly these bytes
     * through its listener.
     */
    static final String GOLDEN_PLAYER_MSG = "7b22616374696f6e223a22706c617965725f6d7367222c22706c61796572223a2231653166326233632d346435652d366637302d383139322d613362346335643665376638222c22706c617965725f6e616d65223a224e6f746368222c22636f6e74656e74223a226869205c2274686572655c22203c3e227d";

    private static JsonObject decode(byte[] encoded) {
        return JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    @DisplayName("a report carries exactly the four documented fields")
    void carriesTheDocumentedFields() {
        JsonObject json = decode(ChatCodec.encodePlayerMessage(PLAYER, "Notch", "hello"));

        assertEquals(4, json.size());
        assertEquals(ChatChannel.ACTION_PLAYER_MSG, json.get("action").getAsString());
        assertEquals(PLAYER.toString(), json.get("player").getAsString());
        assertEquals("Notch", json.get("player_name").getAsString());
        assertEquals("hello", json.get("content").getAsString());
    }

    @Test
    @DisplayName("quotes, backslashes, newlines and non-ASCII survive the round trip")
    void escapesContent() {
        String content = "\"quoted\" \\ back\nslash — Čeština 👋";

        assertEquals(content, decode(ChatCodec.encodePlayerMessage(PLAYER, "Notch", content))
                .get("content").getAsString());
    }

    /**
     * Pins the exact bytes the proxy must understand, as
     * {@code ProtocolCodecTest.goldenBytes} does for the language channel.
     */
    @Test
    @DisplayName("the encoding matches the documented layout, byte for byte")
    void goldenBytes() {
        byte[] encoded = ChatCodec.encodePlayerMessage(PLAYER, "Notch", "hi \"there\" <>");

        assertEquals(GOLDEN_PLAYER_MSG, HexFormat.of().formatHex(encoded));
    }
}
