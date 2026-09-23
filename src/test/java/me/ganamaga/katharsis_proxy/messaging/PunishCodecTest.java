package me.ganamaga.katharsis_proxy.messaging;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fixtures are shared with the companion's {@code PunishCodecTest}, so the
 * two sides are held to the same bytes.
 */
class PunishCodecTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    static final String MUTED = "000a6d7574655f7374617465002431653166326233632d346435652d366637302d383139322d61336234633564366537663801000001a0c4506c00000000000000002a0000000200036d7367000474656c6c";
    static final String PERMANENT = "000a6d7574655f7374617465002431653166326233632d346435652d366637302d383139322d61336234633564366537663801ffffffffffffffff000000000000000700000000";
    static final String UNMUTED = "000a6d7574655f7374617465002431653166326233632d346435652d366637302d383139322d61336234633564366537663800";
    static final String QUERY = "000a6d7574655f7175657279002431653166326233632d346435652d366637302d383139322d613362346335643665376638";
    static final String ATTEMPT = "000d6d757465645f617474656d7074002431653166326233632d346435652d366637302d383139322d613362346335643665376638";

    private static Punishment mute(long id, Instant expiresAt) {
        return new Punishment(id, PunishmentType.MUTE, PLAYER, "Notch", null, PunishmentScope.global(), "spam",
                null, IssuerRef.console(), false, null, Instant.ofEpochMilli(1_700_000_000_000L), expiresAt, null);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    @DisplayName("mute states encode to the documented bytes")
    void encodesStates() {
        assertEquals(MUTED, hex(PunishCodec.encodeMuteState(PLAYER,
                mute(42, Instant.ofEpochMilli(1_790_000_000_000L)), List.of("msg", "tell"))));
        assertEquals(PERMANENT, hex(PunishCodec.encodeMuteState(PLAYER, mute(7, null), List.of())));
        assertEquals(UNMUTED, hex(PunishCodec.encodeMuteState(PLAYER, null, List.of("msg"))));
    }

    @Test
    @DisplayName("queries and attempts from the companion decode")
    void decodesIncoming() {
        assertEquals(new PunishCodec.Incoming(PunishChannel.OP_MUTE_QUERY, PLAYER),
                PunishCodec.decode(HexFormat.of().parseHex(QUERY)));
        assertEquals(new PunishCodec.Incoming(PunishChannel.OP_MUTED_ATTEMPT, PLAYER),
                PunishCodec.decode(HexFormat.of().parseHex(ATTEMPT)));
        assertNull(PunishCodec.decode(new byte[0]));
        assertNull(PunishCodec.decode(HexFormat.of().parseHex("000a6d7574655f71756572790003616263")));
    }
}
