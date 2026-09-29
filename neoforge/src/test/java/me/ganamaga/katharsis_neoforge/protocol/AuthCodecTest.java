package me.ganamaga.katharsis_neoforge.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fixtures are shared with the proxy's {@code AuthCodecTest}, so the two
 * sides are held to the same bytes.
 */
class AuthCodecTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    static final String UNLOCK = "0006756e6c6f636b002431653166326233632d346435652d366637302d383139322d613362346335643665376638";
    static final String LOCKED = "00066c6f636b6564002431653166326233632d346435652d366637302d383139322d6133623463356436653766380001" + "31";

    @Test
    @DisplayName("locked encodes to the documented bytes")
    void encodesLocked() {
        assertEquals(LOCKED, HexFormat.of().formatHex(AuthCodec.encodeLocked(PLAYER)));
    }

    @Test
    @DisplayName("unlock from the proxy decodes")
    void decodesUnlock() {
        assertEquals(new AuthCodec.Incoming(AuthCodec.OP_UNLOCK, PLAYER),
                AuthCodec.decode(HexFormat.of().parseHex(UNLOCK)));
    }

    @Test
    @DisplayName("garbage is refused rather than misread")
    void refusesGarbage() {
        assertNull(AuthCodec.decode(new byte[0]));
        assertNull(AuthCodec.decode(HexFormat.of().parseHex("0006756e6c6f636b0003616263")));
        assertNull(AuthCodec.decode(HexFormat.of().parseHex("0006756e6c6f636b")));
    }
}
