package me.ganamaga.katharsis_proxy.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fixtures are shared with the mod's {@code IdentityCodecTest}, so the two
 * sides are held to the same bytes.
 */
class IdentityCodecTest {

    static final String KEY = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394";
    static final String REQUEST = "000c636572745f7265717565737400144b61746861727369732d6964656e746974792d31000131" + KEY;
    static final String FAILURE = "00046365727400017800000e756e6b6e6f776e5f736572766572";
    /** A reply carrying the three bytes 010203 as its certificate and one rotation of 141 bytes of 07. */
    static final String CERT = "000463657274000178010000000301020301" + "07".repeat(141);

    @Test
    @DisplayName("a cert_request from the mod decodes")
    void decodesRequest() {
        IdentityCodec.Request request = IdentityCodec.decodeRequest(HexFormat.of().parseHex(REQUEST));

        assertEquals(IdentityChannel.OP_CERT_REQUEST, request.operation());
        assertEquals("Katharsis-identity-1", request.requestId());
        assertEquals(IdentityChannel.PROTOCOL, request.protocol());
        assertArrayEquals(HexFormat.of().parseHex(KEY), request.key());
    }

    @Test
    @DisplayName("replies encode to the documented bytes")
    void encodesReplies() {
        assertEquals(FAILURE, HexFormat.of().formatHex(
                IdentityCodec.encodeFailure("x", IdentityChannel.ERROR_UNKNOWN_SERVER)));
        byte[] rotation = new byte[141];
        java.util.Arrays.fill(rotation, (byte) 7);
        assertEquals(CERT, HexFormat.of().formatHex(
                IdentityCodec.encodeCertificate("x", new byte[]{1, 2, 3}, List.of(rotation))));
    }

    @Test
    @DisplayName("a short key or a truncated request is refused rather than misread")
    void refusesGarbage() {
        assertNull(IdentityCodec.decodeRequest(new byte[0]));
        assertNull(IdentityCodec.decodeRequest(HexFormat.of().parseHex(REQUEST.substring(0, REQUEST.length() - 2))));
    }
}
