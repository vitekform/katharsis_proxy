package me.ganamaga.katharsis_neoforge.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures are shared with the proxy's {@code IdentityCodecTest}, so the
 * two sides are held to the same bytes.
 */
class IdentityCodecTest {

    static final String KEY = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394";
    static final String REQUEST = "000c636572745f7265717565737400144b61746861727369732d6964656e746974792d31000131" + KEY;
    static final String FAILURE = "00046365727400017800000e756e6b6e6f776e5f736572766572";
    static final String CERT = "000463657274000178010000000301020301" + "07".repeat(141);

    @Test
    @DisplayName("cert_request encodes to the documented bytes")
    void encodesRequest() {
        assertEquals(REQUEST, HexFormat.of().formatHex(
                IdentityCodec.encodeRequest("Katharsis-identity-1", HexFormat.of().parseHex(KEY))));
    }

    @Test
    @DisplayName("the proxy's replies decode")
    void decodesReplies() {
        IdentityCodec.Reply failure = IdentityCodec.decodeReply(HexFormat.of().parseHex(FAILURE));
        assertFalse(failure.success());
        assertEquals("unknown_server", failure.error());

        IdentityCodec.Reply cert = IdentityCodec.decodeReply(HexFormat.of().parseHex(CERT));
        assertTrue(cert.success());
        assertEquals("x", cert.requestId());
        assertArrayEquals(new byte[]{1, 2, 3}, cert.certificate());
        assertEquals(1, cert.rotations().size());
        assertEquals(141, cert.rotations().getFirst().length);
    }

    @Test
    @DisplayName("a truncated or foreign reply is refused rather than misread")
    void refusesGarbage() {
        assertNull(IdentityCodec.decodeReply(new byte[0]));
        assertNull(IdentityCodec.decodeReply(HexFormat.of().parseHex(CERT.substring(0, CERT.length() - 2))));
        assertNull(IdentityCodec.decodeReply(HexFormat.of().parseHex("0006756e6c6f636b")));
    }
}
