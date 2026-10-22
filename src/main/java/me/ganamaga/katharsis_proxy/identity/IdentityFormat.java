package me.ganamaga.katharsis_proxy.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The GNLib identity structures the proxy signs: fingerprints, network
 * certificates and rotation statements, byte for byte as GNLib's
 * {@code docs/remote/identity.md} lays them out, so a GNLib client checks
 * them exactly as it checks its own.
 *
 * <p>Free of Velocity types. GNLib is not a dependency: the two share a wire
 * format, not code, and the tests pin the bytes GNLib's own code produces.</p>
 */
public final class IdentityFormat {

    /** A rotation statement of the network key, as opposed to a single server's key (0). */
    public static final int KIND_NETWORK = 1;

    public static final int RAW_BYTES = 32;
    public static final int SIGNATURE_BYTES = 64;
    public static final int ROTATION_BYTES = 141;
    /** At most this many rotation statements are presented. */
    public static final int MAX_ROTATIONS = 8;
    /** The longest backend name a certificate carries, in UTF-8 bytes. */
    public static final int MAX_NAME_BYTES = 64;
    /** Every time field lies in 0 to 2^40. */
    public static final long MAX_TIME = 1L << 40;

    private static final String FINGERPRINT_LABEL = "GNLib identity v1";
    private static final String ROTATION_CONTEXT = "GNLib remote rotation v1";
    private static final String CERTIFICATE_CONTEXT = "GNLib remote certificate v1";
    private static final byte[] ROTATION_MAGIC = {'G', 'N', 'R', '1'};
    private static final byte[] CERTIFICATE_MAGIC = {'G', 'N', 'C', '1'};
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING }: Java's X.509 form of an Ed25519 key is this and the raw key. */
    private static final byte[] ED25519_PREFIX = java.util.HexFormat.of().parseHex("302a300506032b6570032100");

    private IdentityFormat() {
    }

    /** A key or structure that cannot be used. */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        public Invalid(String message) {
            super(message);
        }
    }

    // --- fingerprints --------------------------------------------------------

    /** {@code GN1:} and eight groups of four base32 characters of {@code SHA-256("GNLib identity v1" || 0 || key)}. */
    public static String fingerprint(byte[] rawPublic) {
        byte[] digest;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(FINGERPRINT_LABEL.getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(rawPublic);
            digest = sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no SHA-256", e);
        }
        String base32 = base32(Arrays.copyOf(digest, 20));
        StringBuilder out = new StringBuilder("GN1:");
        for (int i = 0; i < base32.length(); i += 4) {
            out.append(i > 0 ? "-" : "").append(base32, i, i + 4);
        }
        return out.toString();
    }

    /** RFC 4648 base32 without padding, of a whole number of five-byte groups. */
    private static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return out.toString();
    }

    // --- certificates and rotations ------------------------------------------

    /**
     * A network certificate: {@code issuer} (the network key) vouches for
     * {@code subject} (a backend's key) from {@code notBefore} to
     * {@code notAfter}, Unix seconds.
     */
    public static byte[] certificate(PrivateKey issuerPrivate, byte[] issuer, byte[] subject, long notBefore,
                                     long notAfter, String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("backend name over " + MAX_NAME_BYTES + " bytes: " + name);
        }
        ByteBuffer body = ByteBuffer.allocate(86 + nameBytes.length + SIGNATURE_BYTES)
                .put(CERTIFICATE_MAGIC).put(issuer).put(subject).putLong(notBefore).putLong(notAfter)
                .put((byte) nameBytes.length).put(nameBytes).put((byte) 0);
        byte[] signed = Arrays.copyOf(body.array(), 86 + nameBytes.length);
        body.put(sign(issuerPrivate, CERTIFICATE_CONTEXT, signed));
        return body.array();
    }

    /** A 141-byte statement, signed by the old key, that the network key changed to {@code newKey}. */
    public static byte[] rotation(PrivateKey oldPrivate, byte[] oldKey, byte[] newKey, long issuedAt) {
        ByteBuffer body = ByteBuffer.allocate(ROTATION_BYTES)
                .put(ROTATION_MAGIC).put((byte) KIND_NETWORK).put(oldKey).put(newKey).putLong(issuedAt);
        byte[] signed = Arrays.copyOf(body.array(), 77);
        body.put(sign(oldPrivate, ROTATION_CONTEXT, signed));
        return body.array();
    }

    /** The issue time of a rotation statement this proxy wrote, after checking its shape and signature. */
    public static long rotationIssuedAt(byte[] statement) throws Invalid {
        if (statement.length != ROTATION_BYTES || !Arrays.equals(statement, 0, 4, ROTATION_MAGIC, 0, 4)
                || statement[4] != KIND_NETWORK) {
            throw new Invalid("not a network rotation statement");
        }
        byte[] oldKey = Arrays.copyOfRange(statement, 5, 37);
        if (!verify(oldKey, ROTATION_CONTEXT, Arrays.copyOf(statement, 77), Arrays.copyOfRange(statement, 77, 141))) {
            throw new Invalid("rotation statement is not signed by the key it rotates from");
        }
        long issuedAt = ByteBuffer.wrap(statement, 69, 8).getLong();
        if (issuedAt < 0 || issuedAt > MAX_TIME) {
            throw new Invalid("rotation statement has an issue time out of range");
        }
        return issuedAt;
    }

    /** Signs {@code context || 0x00 || message}, as every GNLib identity signature is made. */
    static byte[] sign(PrivateKey key, String context, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(context.getBytes(StandardCharsets.US_ASCII));
            signature.update((byte) 0);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign with the network key", e);
        }
    }

    static boolean verify(byte[] rawPublic, String context, byte[] message, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey(rawPublic));
            signature.update(context.getBytes(StandardCharsets.US_ASCII));
            signature.update((byte) 0);
            signature.update(message);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException | Invalid | RuntimeException e) {
            return false;
        }
    }

    // --- keys --------------------------------------------------------------------

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no Ed25519", e);
        }
    }

    /** The raw 32-byte form of an Ed25519 public key. */
    public static byte[] raw(PublicKey key) throws Invalid {
        byte[] encoded = key.getEncoded();
        if (encoded == null || encoded.length != ED25519_PREFIX.length + RAW_BYTES
                || !Arrays.equals(encoded, 0, ED25519_PREFIX.length, ED25519_PREFIX, 0, ED25519_PREFIX.length)) {
            throw new Invalid("not an encoded Ed25519 public key");
        }
        return Arrays.copyOfRange(encoded, ED25519_PREFIX.length, encoded.length);
    }

    static PublicKey publicKey(byte[] raw) throws Invalid {
        if (raw == null || raw.length != RAW_BYTES) {
            throw new Invalid("an Ed25519 public key is 32 bytes");
        }
        byte[] encoded = Arrays.copyOf(ED25519_PREFIX, ED25519_PREFIX.length + RAW_BYTES);
        System.arraycopy(raw, 0, encoded, ED25519_PREFIX.length, RAW_BYTES);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new Invalid("not a valid Ed25519 public key: " + e.getMessage());
        }
    }

    /** PKCS#8 PEM, as {@code openssl pkey} reads it. */
    public static String privatePem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    /** X.509 PEM. */
    public static String publicPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** Reads a key pair from its PKCS#8 PEM; the public half is derived from the private. */
    public static KeyPair readPrivatePem(String text) throws Invalid {
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        if (from < 0 || to < from) {
            throw new Invalid("no PKCS#8 private key block");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to));
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            return new KeyPair(publicKey(publicOf(privateKey)), privateKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Invalid("not an Ed25519 private key: " + e.getMessage());
        }
    }

    /**
     * The raw public key of an Ed25519 private key. Java has no API for it, so
     * the JDK's generator is run on the key's own seed (RFC 8032 derives the
     * pair from it deterministically), and the result is checked by signing
     * with the stored key: a wrong derivation refuses the key instead of
     * misusing it.
     */
    private static byte[] publicOf(PrivateKey privateKey) throws Invalid {
        if (!(privateKey instanceof EdECPrivateKey edec) || edec.getBytes().isEmpty()) {
            throw new Invalid("not an Ed25519 private key with a seed");
        }
        byte[] seed = edec.getBytes().get();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(255, new SeedRandom(seed));
            byte[] raw = raw(generator.generateKeyPair().getPublic());
            if (!verify(raw, "Katharsis key check", seed, sign(privateKey, "Katharsis key check", seed))) {
                throw new Invalid("could not derive the public half of the key");
            }
            return raw;
        } catch (GeneralSecurityException e) {
            throw new Invalid("could not derive the public half of the key: " + e.getMessage());
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }

    /** Hands the generator one fixed seed: the only bytes it asks for. */
    private static final class SeedRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;

        private final byte[] seed;

        SeedRandom(byte[] seed) {
            super(new byte[0]);
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != seed.length) {
                throw new IllegalStateException("unexpected request for " + bytes.length + " random bytes");
            }
            System.arraycopy(seed, 0, bytes, 0, seed.length);
        }
    }
}
