package me.ganamaga.katharsis_proxy.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fixtures are shared with the NeoForge mod's {@code AuthCodecTest}, so the
 * two sides are held to the same bytes.
 */
class AuthCodecTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    static final String UNLOCK = "0006756e6c6f636b002431653166326233632d346435652d366637302d383139322d613362346335643665376638";
    static final String LOCKED = "00066c6f636b6564002431653166326233632d346435652d366637302d383139322d6133623463356436653766380001" + "31";

    @Test
    @DisplayName("unlock encodes to the documented bytes")
    void encodesUnlock() {
        assertEquals(UNLOCK, HexFormat.of().formatHex(AuthCodec.encodeUnlock(PLAYER)));
    }

    @Test
    @DisplayName("locked from the mod decodes with its protocol")
    void decodesLocked() {
        assertEquals(new AuthCodec.Incoming(AuthChannel.OP_LOCKED, PLAYER, AuthChannel.PROTOCOL),
                AuthCodec.decode(HexFormat.of().parseHex(LOCKED)));
    }

    @Test
    @DisplayName("a message without a protocol decodes with an empty one")
    void decodesWithoutProtocol() {
        String bare = LOCKED.substring(0, LOCKED.length() - "000131".length());
        assertEquals(new AuthCodec.Incoming(AuthChannel.OP_LOCKED, PLAYER, ""),
                AuthCodec.decode(HexFormat.of().parseHex(bare)));
    }

    @Test
    @DisplayName("garbage is refused rather than misread")
    void refusesGarbage() {
        assertNull(AuthCodec.decode(new byte[0]));
        assertNull(AuthCodec.decode(HexFormat.of().parseHex("00066c6f636b65640003616263")));
        assertNull(AuthCodec.decode(HexFormat.of().parseHex("00066c6f636b6564")));
    }
}
