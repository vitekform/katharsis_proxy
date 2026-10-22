package me.ganamaga.katharsis_neoforge.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

/**
 * The proxy's half, for the tests only: the mod never signs a certificate.
 * {@link IdentityFormatTest} holds both halves to GNLib's bytes.
 */
final class Signing {

    private Signing() {
    }

    /** The key whose RFC 8032 seed is 32 bytes of {@code fill}. */
    static KeyPair seeded(int fill) throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        return IdentityFormat.readPrivatePem(IdentityFormat.privatePem(key));
    }

    static byte[] raw(KeyPair pair) throws IdentityFormat.Invalid {
        return IdentityFormat.raw(pair.getPublic());
    }

    static byte[] certificate(KeyPair issuer, byte[] subject, long notBefore, long notAfter, String name)
            throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.allocate(86 + nameBytes.length + 64)
                .put(new byte[]{'G', 'N', 'C', '1'}).put(raw(issuer)).put(subject).putLong(notBefore)
                .putLong(notAfter).put((byte) nameBytes.length).put(nameBytes).put((byte) 0);
        body.put(sign(issuer.getPrivate(), "GNLib remote certificate v1", Arrays.copyOf(body.array(), 86 + nameBytes.length)));
        return body.array();
    }

    static byte[] rotation(KeyPair from, byte[] to, long issuedAt) throws Exception {
        ByteBuffer body = ByteBuffer.allocate(141)
                .put(new byte[]{'G', 'N', 'R', '1'}).put((byte) 1).put(raw(from)).put(to).putLong(issuedAt);
        body.put(sign(from.getPrivate(), "GNLib remote rotation v1", Arrays.copyOf(body.array(), 77)));
        return body.array();
    }

    private static byte[] sign(PrivateKey key, String context, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(context.getBytes(StandardCharsets.US_ASCII));
        signature.update((byte) 0);
        signature.update(message);
        return signature.sign();
    }
}
