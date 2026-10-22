package me.ganamaga.katharsis_neoforge.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures are GNLib's own bytes for these keys and fields, shared with the
 * proxy's {@code IdentityFormatTest}: a certificate GNLib's code wrote passes
 * here, and the proxy writes the same bytes.
 */
class IdentityFormatTest {

    static final String NETWORK_KEY = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c";
    static final String NETWORK_FINGERPRINT = "GN1:HW75-WKQJ-MMHW-FDOY-IV54-NVF6-7IBI-OM3L";
    static final String BACKEND_KEY = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394";
    static final String CERTIFICATE = "474e43318a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
            + "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394000000006b49d200000000006b4c7500"
            + "056c6f626279006ab1a498a9b4081925236e0c8bac6ad63591ca756b578fb668e6612ad13a314cc9d5754955ab4e3ffa"
            + "6c4f154c1f4b92154d97f3deebc6e3b6113a64622d3a09";
    static final String ROTATION = "474e5231018a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
            + "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1000000006b49d200d9facd4f48463121"
            + "915e255c9f5cd1d56098068a4a3b25bb226882fdc69d98c4226c76da086e88599264fb94df1d02e3dd2db945d3b4d480"
            + "f9e38d0cc3756005";

    private static final long ISSUED = 1_800_000_000L;

    @Test
    @DisplayName("fingerprints match GNLib's, however they are typed")
    void fingerprints() {
        assertEquals(NETWORK_FINGERPRINT, IdentityFormat.fingerprint(HexFormat.of().parseHex(NETWORK_KEY)));
        assertEquals(NETWORK_FINGERPRINT, IdentityFormat.normalise(" hw75 wkqj-mmhwfdoy iv54 nvf6 7ibi om3l"));
        assertNull(IdentityFormat.normalise("GN1:HW75"));
        assertNull(IdentityFormat.normalise("GN1:HW75-WKQJ-MMHW-FDOY-IV54-NVF6-7IBI-OM31"), "1 is not base32");
    }

    @Test
    @DisplayName("GNLib's certificate passes and reads back")
    void certificate() throws Exception {
        IdentityFormat.Certificate certificate = IdentityFormat.checkCertificate(
                HexFormat.of().parseHex(CERTIFICATE), ISSUED + 60);

        assertArrayEquals(HexFormat.of().parseHex(NETWORK_KEY), certificate.issuer());
        assertArrayEquals(HexFormat.of().parseHex(BACKEND_KEY), certificate.subject());
        assertEquals("lobby", certificate.name());
        assertEquals(ISSUED + 172_800L, certificate.notAfter());
        assertArrayEquals(HexFormat.of().parseHex(CERTIFICATE), Signing.certificate(Signing.seeded(1),
                HexFormat.of().parseHex(BACKEND_KEY), ISSUED, ISSUED + 172_800L, "lobby"), "the tests' signer agrees");
    }

    @Test
    @DisplayName("a certificate is refused when altered, too long, or not valid now")
    void refusals() throws Exception {
        byte[] altered = HexFormat.of().parseHex(CERTIFICATE);
        altered[90] ^= 1;
        assertThrows(IdentityFormat.Invalid.class, () -> IdentityFormat.checkCertificate(altered, ISSUED));
        byte[] eightDays = Signing.certificate(Signing.seeded(1), HexFormat.of().parseHex(BACKEND_KEY),
                ISSUED, ISSUED + 8 * 86_400L, "lobby");
        assertThrows(IdentityFormat.Invalid.class, () -> IdentityFormat.checkCertificate(eightDays, ISSUED));
        byte[] good = HexFormat.of().parseHex(CERTIFICATE);
        assertThrows(IdentityFormat.Invalid.class, () -> IdentityFormat.checkCertificate(good, ISSUED - 601));
        assertThrows(IdentityFormat.Invalid.class, () -> IdentityFormat.checkCertificate(good, ISSUED + 172_800L + 601));
        IdentityFormat.checkCertificate(good, ISSUED - 600);
    }

    @Test
    @DisplayName("GNLib's rotation statement parses, and a chain is followed from a fingerprint")
    void rotation() throws Exception {
        IdentityFormat.Rotation rotation = IdentityFormat.parseRotation(HexFormat.of().parseHex(ROTATION));
        byte[] next = Signing.raw(Signing.seeded(3));

        assertEquals(IdentityFormat.KIND_NETWORK, rotation.kind());
        assertArrayEquals(next, rotation.newKey());
        assertTrue(IdentityFormat.chainLeads(List.of(rotation), NETWORK_FINGERPRINT, next));
        assertFalse(IdentityFormat.chainLeads(List.of(rotation), IdentityFormat.fingerprint(next), next));
        byte[] fourth = Signing.raw(Signing.seeded(4));
        IdentityFormat.Rotation second = IdentityFormat.parseRotation(Signing.rotation(Signing.seeded(3), fourth, ISSUED + 1));
        assertTrue(IdentityFormat.chainLeads(List.of(rotation, second), NETWORK_FINGERPRINT, fourth));
        assertTrue(IdentityFormat.chainLeads(List.of(rotation, second), IdentityFormat.fingerprint(next), fourth),
                "a pin taken between the two rotations follows only the second");
        assertFalse(IdentityFormat.chainLeads(List.of(second, rotation), NETWORK_FINGERPRINT, fourth));
    }
}
