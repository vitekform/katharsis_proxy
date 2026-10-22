package me.ganamaga.katharsis_proxy.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Holds the proxy to the bytes GNLib's own {@code Identity} class produces for
 * the same keys and fields (Ed25519 signatures are deterministic). The NeoForge
 * mod's {@code IdentityFormatTest} checks the same fixtures.
 */
class IdentityFormatTest {

    static final String NETWORK_KEY = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c";
    static final String NETWORK_FINGERPRINT = "GN1:HW75-WKQJ-MMHW-FDOY-IV54-NVF6-7IBI-OM3L";
    static final String BACKEND_KEY = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394";
    /** Network key over the backend key, as "lobby", from 1800000000 for 48 hours: GNLib's bytes. */
    static final String CERTIFICATE = "474e43318a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
            + "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394000000006b49d200000000006b4c7500"
            + "056c6f626279006ab1a498a9b4081925236e0c8bac6ad63591ca756b578fb668e6612ad13a314cc9d5754955ab4e3ffa"
            + "6c4f154c1f4b92154d97f3deebc6e3b6113a64622d3a09";
    /** Network key to the key of seed 3, issued at 1800000000: GNLib's bytes. */
    static final String ROTATION = "474e5231018a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
            + "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1000000006b49d200d9facd4f48463121"
            + "915e255c9f5cd1d56098068a4a3b25bb226882fdc69d98c4226c76da086e88599264fb94df1d02e3dd2db945d3b4d480"
            + "f9e38d0cc3756005";

    /** The key whose RFC 8032 seed is 32 bytes of {@code fill}, read back as the proxy reads network.key. */
    static KeyPair seeded(int fill) throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        return IdentityFormat.readPrivatePem(IdentityFormat.privatePem(key));
    }

    @Test
    @DisplayName("keys and fingerprints match GNLib's")
    void keysAndFingerprints() throws Exception {
        byte[] network = IdentityFormat.raw(seeded(1).getPublic());
        assertEquals(NETWORK_KEY, HexFormat.of().formatHex(network));
        assertEquals(BACKEND_KEY, HexFormat.of().formatHex(IdentityFormat.raw(seeded(2).getPublic())));
        assertEquals(NETWORK_FINGERPRINT, IdentityFormat.fingerprint(network));
    }

    @Test
    @DisplayName("a certificate is byte for byte what GNLib writes")
    void certificate() throws Exception {
        KeyPair network = seeded(1);
        byte[] certificate = IdentityFormat.certificate(network.getPrivate(), IdentityFormat.raw(network.getPublic()),
                HexFormat.of().parseHex(BACKEND_KEY), 1_800_000_000L, 1_800_000_000L + 172_800L, "lobby");
        assertEquals(CERTIFICATE, HexFormat.of().formatHex(certificate));
    }

    @Test
    @DisplayName("a rotation statement is byte for byte what GNLib writes, and reads back")
    void rotation() throws Exception {
        KeyPair network = seeded(1);
        byte[] statement = IdentityFormat.rotation(network.getPrivate(), IdentityFormat.raw(network.getPublic()),
                IdentityFormat.raw(seeded(3).getPublic()), 1_800_000_000L);
        assertEquals(ROTATION, HexFormat.of().formatHex(statement));
        assertEquals(1_800_000_000L, IdentityFormat.rotationIssuedAt(statement));
    }
}
