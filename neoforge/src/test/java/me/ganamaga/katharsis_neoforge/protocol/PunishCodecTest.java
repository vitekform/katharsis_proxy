package me.ganamaga.katharsis_neoforge.protocol;

import me.ganamaga.katharsis_neoforge.mute.MuteState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fixtures are shared with the proxy's {@code PunishCodecTest}, so the two
 * sides are held to the same bytes.
 */
class PunishCodecTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    /** Muted until 1790000000000 by #42, blocking msg and tell. */
    static final String MUTED = "000a6d7574655f7374617465002431653166326233632d346435652d366637302d383139322d61336234633564366537663801000001a0c4506c00000000000000002a0000000200036d7367000474656c6c";
    /** Muted for good by #7, blocking no commands. */
    static final String PERMANENT = "000a6d7574655f7374617465002431653166326233632d346435652d366637302d383139322d61336234633564366537663801ffffffffffffffff000000000000000700000000";
    static final String UNMUTED = "000a6d7574655f7374617465002431653166326233632d346435652d366637302d383139322d61336234633564366537663800";
    static final String QUERY = "000a6d7574655f7175657279002431653166326233632d346435652d366637302d383139322d613362346335643665376638";
    static final String ATTEMPT = "000d6d757465645f617474656d7074002431653166326233632d346435652d366637302d383139322d613362346335643665376638";

    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    @Test
    @DisplayName("mute states decode with their end, id and blocked commands")
    void decodesStates() {
        assertEquals(new PunishCodec.Update(PLAYER, new MuteState(1_790_000_000_000L, 42, List.of("msg", "tell"))),
                PunishCodec.decodeState(bytes(MUTED)));
        assertEquals(new PunishCodec.Update(PLAYER, new MuteState(PunishChannel.PERMANENT, 7, List.of())),
                PunishCodec.decodeState(bytes(PERMANENT)));
        assertEquals(new PunishCodec.Update(PLAYER, null), PunishCodec.decodeState(bytes(UNMUTED)));
    }

    @Test
    @DisplayName("queries and attempts encode to the documented bytes")
    void encodesRequests() {
        assertEquals(QUERY, HexFormat.of().formatHex(PunishCodec.encodeQuery(PLAYER)));
        assertEquals(ATTEMPT, HexFormat.of().formatHex(PunishCodec.encodeAttempt(PLAYER)));
    }

    @Test
    @DisplayName("anything else is not a mute state")
    void rejectsOthers() {
        assertNull(PunishCodec.decodeState(bytes(QUERY)), "another operation");
        assertNull(PunishCodec.decodeState(bytes(MUTED.substring(0, MUTED.length() - 6))), "cut short");
        assertNull(PunishCodec.decodeState(new byte[0]));
        assertNull(PunishCodec.decodeState(bytes("000a6d7574655f73746174650003616263")), "not a uuid");
    }
}
