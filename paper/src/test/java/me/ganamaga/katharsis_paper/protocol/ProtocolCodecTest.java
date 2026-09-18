package me.ganamaga.katharsis_paper.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {

    private static final String PLAYER = "1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";

    /**
     * {@code announce} with request id {@code r1}, the nil uuid and version
     * {@code 0.1}, as this codec writes it. The proxy has a test that feeds
     * exactly these bytes through its listener.
     */
    static final String GOLDEN_ANNOUNCE = "0008616e6e6f756e636500027231002430303030303030302d303030302d303030302d303030302d3030303030303030303030300003302e31";

    /**
     * {@code announce} as above, from a server with {@code server-id: lobby},
     * which travels in a fifth field. The proxy pins these bytes too.
     */
    static final String GOLDEN_ANNOUNCE_WITH_SERVER_ID = GOLDEN_ANNOUNCE + "00056c6f626279";

    // --- requests ----------------------------------------------------------

    @Test
    @DisplayName("a get request is three fields, with no payload")
    void getRequestHasNoPayload() throws IOException {
        byte[] encoded = ProtocolCodec.encodeRequest(LanguageChannel.OP_GET, "r1", PLAYER, "ignored");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(LanguageChannel.OP_GET, in.readUTF());
        assertEquals("r1", in.readUTF());
        assertEquals(PLAYER, in.readUTF());
        assertEquals(0, in.available(), "the fourth field must be absent for get_language");
    }

    @Test
    @DisplayName("a set request carries the language in the fourth field")
    void setRequestCarriesLanguage() throws IOException {
        byte[] encoded = ProtocolCodec.encodeRequest(LanguageChannel.OP_SET, "r2", PLAYER, "cs_CZ");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(LanguageChannel.OP_SET, in.readUTF());
        assertEquals("r2", in.readUTF());
        assertEquals(PLAYER, in.readUTF());
        assertEquals("cs_CZ", in.readUTF());
        assertEquals(0, in.available());
    }

    @Test
    @DisplayName("an announce request carries the version in the fourth field")
    void announceRequestCarriesVersion() throws IOException {
        byte[] encoded = ProtocolCodec.encodeRequest(LanguageChannel.OP_ANNOUNCE, "r3", PLAYER, "0.1");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(LanguageChannel.OP_ANNOUNCE, in.readUTF());
        in.readUTF();
        in.readUTF();
        assertEquals("0.1", in.readUTF());
    }

    /**
     * Pins the exact bytes the proxy must understand. The two plugins are
     * separate artifacts that repeat the protocol constants, so this golden
     * value is asserted on both sides: the proxy has the matching test feeding
     * these very bytes through its listener.
     */
    @Test
    @DisplayName("the encoding matches the documented layout, byte for byte")
    void goldenBytes() {
        byte[] encoded = ProtocolCodec.encodeRequest(LanguageChannel.OP_ANNOUNCE, "r1",
                "00000000-0000-0000-0000-000000000000", "0.1");

        assertEquals(GOLDEN_ANNOUNCE, HexFormat.of().formatHex(encoded));
    }

    @Test
    @DisplayName("an announcement carries the server id in a fifth field, empty when none is set")
    void announceCarriesServerId() throws IOException {
        assertEquals(GOLDEN_ANNOUNCE_WITH_SERVER_ID,
                HexFormat.of().formatHex(ProtocolCodec.encodeAnnounce("r1", "0.1", "lobby")));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(ProtocolCodec.encodeAnnounce("r1", "0.1", "")));
        for (int field = 0; field < 4; field++) {
            in.readUTF();
        }
        assertEquals("", in.readUTF());
        assertEquals(0, in.available());
    }

    // --- responses ---------------------------------------------------------

    /**
     * The proxy's unasked {@code open_language_menu} push, byte for byte as its
     * {@code FirstJoinMenuListenerTest.GOLDEN_OPEN_MENU} pins it.
     */
    @Test
    @DisplayName("the proxy's first-join push decodes with the ordinary response reader")
    void decodesOpenMenuPush() {
        ProxyResponse decoded = ProtocolCodec.decodeResponse(HexFormat.of().parseHex(
                "00126f70656e5f6c616e67756167655f6d656e750000002431653166326233632d346435652d"
                        + "366637302d383139322d6133623463356436653766380100000000"));

        assertEquals(LanguageChannel.OP_OPEN_MENU, decoded.operation());
        assertEquals("", decoded.requestId(), "nothing asked for it");
        assertEquals(PLAYER, decoded.uuid());
        assertTrue(decoded.success());
    }

    @Test
    @DisplayName("a plain response decodes into its six fields")
    void decodesPlainResponse() throws IOException {
        byte[] reply = response(LanguageChannel.OP_SET, "r4", PLAYER, true, "cs_CZ", "");

        ProxyResponse decoded = ProtocolCodec.decodeResponse(reply);

        assertEquals(LanguageChannel.OP_SET, decoded.operation());
        assertEquals("r4", decoded.requestId());
        assertEquals(PLAYER, decoded.uuid());
        assertTrue(decoded.success());
        assertEquals("cs_CZ", decoded.language());
        assertEquals(LanguageChannel.ERROR_NONE, decoded.error());
        assertTrue(decoded.menuData().isEmpty(), "only a menu reply carries menu data");
    }

    @Test
    @DisplayName("a failure response keeps its error code")
    void decodesFailure() throws IOException {
        byte[] reply = response(LanguageChannel.OP_GET, "r5", PLAYER, false, "", "unknown_player");

        ProxyResponse decoded = ProtocolCodec.decodeResponse(reply);

        assertFalse(decoded.success());
        assertEquals("unknown_player", decoded.error());
    }

    @Test
    @DisplayName("a menu response decodes the extra fields, in order")
    void decodesMenu() throws IOException {
        LinkedHashMap<String, String> languages = new LinkedHashMap<>();
        languages.put("cs_CZ", "Čeština");
        languages.put("en_US", "English (US)");

        ProxyResponse decoded = ProtocolCodec.decodeResponse(
                menuResponse("r6", PLAYER, "cs_CZ", "<b>Title", "select", "current", languages));

        assertTrue(decoded.success());
        assertEquals("cs_CZ", decoded.language());

        ProxyResponse.Menu menu = decoded.requireMenu();
        assertEquals("<b>Title", menu.title());
        assertEquals("select", menu.selectHint());
        assertEquals("current", menu.currentHint());
        assertEquals(languages, menu.languages());
        assertEquals(java.util.List.of("cs_CZ", "en_US"), java.util.List.copyOf(menu.languages().keySet()),
                "menu order is preserved");
    }

    @Test
    @DisplayName("a menu response with no languages is still valid")
    void decodesEmptyMenu() throws IOException {
        ProxyResponse decoded = ProtocolCodec.decodeResponse(
                menuResponse("r7", PLAYER, "en_US", "t", "s", "c", new LinkedHashMap<>()));

        assertTrue(decoded.requireMenu().languages().isEmpty());
    }

    @Test
    @DisplayName("the menu map cannot be mutated through the record")
    void menuIsDefensivelyCopied() throws IOException {
        LinkedHashMap<String, String> languages = new LinkedHashMap<>();
        languages.put("en_US", "English (US)");
        ProxyResponse decoded = ProtocolCodec.decodeResponse(
                menuResponse("r8", PLAYER, "en_US", "t", "s", "c", languages));

        decoded.requireMenu().languages().put("zz_ZZ", "injected");

        assertEquals(1, decoded.requireMenu().languages().size());
    }

    @Test
    @DisplayName("a truncated or foreign payload decodes to nothing")
    void rejectsGarbage() {
        assertNull(ProtocolCodec.decodeResponse(new byte[]{1, 2, 3}));
        assertNull(ProtocolCodec.decodeResponse(new byte[0]));
    }

    @Test
    @DisplayName("a menu claiming a negative language count is rejected")
    void rejectsNegativeCount() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(LanguageChannel.OP_MENU);
            out.writeUTF("r9");
            out.writeUTF(PLAYER);
            out.writeBoolean(true);
            out.writeUTF("en_US");
            out.writeUTF("");
            out.writeUTF("t");
            out.writeUTF("s");
            out.writeUTF("c");
            out.writeInt(-1);
        }

        assertNull(ProtocolCodec.decodeResponse(bytes.toByteArray()));
    }

    @Test
    @DisplayName("an announce reply carries the name the proxy knows this server by")
    void decodesAnnounceServerName() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(LanguageChannel.OP_ANNOUNCE);
            out.writeUTF("r10");
            out.writeUTF(PLAYER);
            out.writeBoolean(true);
            out.writeUTF("");
            out.writeUTF("");
            out.writeUTF("lifesteal");
        }

        ProxyResponse decoded = ProtocolCodec.decodeResponse(bytes.toByteArray());

        assertTrue(decoded.success());
        assertEquals("lifesteal", decoded.serverName());
    }

    @Test
    @DisplayName("a failed announce has no server name, and that is not an error")
    void failedAnnounceHasNoServerName() throws IOException {
        byte[] reply = response(LanguageChannel.OP_ANNOUNCE, "r11", PLAYER, false, "", "malformed_request");

        ProxyResponse decoded = ProtocolCodec.decodeResponse(reply);

        assertFalse(decoded.success());
        assertEquals("", decoded.serverName(), "never null, so callers need no guard");
    }

    @Test
    @DisplayName("other operations report an empty server name")
    void otherOperationsHaveNoServerName() throws IOException {
        byte[] reply = response(LanguageChannel.OP_SET, "r12", PLAYER, true, "cs_CZ", "");

        assertEquals("", ProtocolCodec.decodeResponse(reply).serverName());
    }

    // --- helpers, mirroring what the proxy writes --------------------------

    private static byte[] response(String operation, String requestId, String uuid,
                                   boolean success, String language, String error) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(operation);
            out.writeUTF(requestId);
            out.writeUTF(uuid);
            out.writeBoolean(success);
            out.writeUTF(language);
            out.writeUTF(error);
        }
        return bytes.toByteArray();
    }

    private static byte[] menuResponse(String requestId, String uuid, String language, String title,
                                       String selectHint, String currentHint,
                                       LinkedHashMap<String, String> languages) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(LanguageChannel.OP_MENU);
            out.writeUTF(requestId);
            out.writeUTF(uuid);
            out.writeBoolean(true);
            out.writeUTF(language);
            out.writeUTF("");
            out.writeUTF(title);
            out.writeUTF(selectHint);
            out.writeUTF(currentHint);
            out.writeInt(languages.size());
            for (var entry : languages.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        }
        return bytes.toByteArray();
    }
}
