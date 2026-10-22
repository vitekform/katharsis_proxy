package me.ganamaga.katharsis_neoforge.identity;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The GNLib identity structures this mod checks: fingerprints, network
 * certificates and rotation statements, as GNLib's
 * {@code docs/remote/identity.md} lays them out and checks them.
 *
 * <p>Free of Minecraft and GNLib types, so it is tested without either, and the
 * mod's GNLib-free part can use it. The proxy has its own copy of the signing
 * half; the tests of both hold them to the bytes GNLib's own code produces.</p>
 */
public final class IdentityFormat {

    public static final int KIND_NETWORK = 1;
    public static final int RAW_BYTES = 32;
    public static final int ROTATION_BYTES = 141;
    public static final int MAX_ROTATIONS = 8;
    /** A certificate valid for longer than this is refused even when signed. */
    public static final long MAX_CERTIFICATE_SECONDS = 7L * 24L * 60L * 60L;
    public static final long MAX_TIME = 1L << 40;
    /** How far this server's clock may be off a certificate's validity, as a GNLib client allows. */
    public static final long CLOCK_TOLERANCE_SECONDS = 10L * 60L;

    private static final String FINGERPRINT_LABEL = "GNLib identity v1";
    private static final String ROTATION_CONTEXT = "GNLib remote rotation v1";
    private static final String CERTIFICATE_CONTEXT = "GNLib remote certificate v1";
    private static final byte[] ROTATION_MAGIC = {'G', 'N', 'R', '1'};
    private static final byte[] CERTIFICATE_MAGIC = {'G', 'N', 'C', '1'};
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final byte[] ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private IdentityFormat() {
    }

    /** A key or structure that does not parse or verify. */
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
        StringBuilder base32 = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : Arrays.copyOf(digest, 20)) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                base32.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return group(base32.toString());
    }

    /**
     * A fingerprint as an operator typed it: case, spaces and hyphens ignored,
     * {@code GN1:} optional.
     *
     * @return the display form, or null when it is not 32 base32 characters
     */
    public static String normalise(String typed) {
        String text = typed.strip().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if (text.startsWith("GN1:")) {
            text = text.substring(4);
        }
        if (text.length() != 32 || !text.chars().allMatch(c -> BASE32.indexOf(c) >= 0)) {
            return null;
        }
        return group(text);
    }

    private static String group(String base32) {
        StringBuilder out = new StringBuilder("GN1:");
        for (int i = 0; i < base32.length(); i += 4) {
            out.append(i > 0 ? "-" : "").append(base32, i, i + 4);
        }
        return out.toString();
    }

    // --- certificates ----------------------------------------------------------

    public record Certificate(byte[] issuer, byte[] subject, long notBefore, long notAfter, String name) {
    }

    /**
     * Parses a certificate and checks it as a GNLib client will: shape and
     * flags, signed by the issuer it names, at most seven days long, and valid
     * at {@code nowSeconds} within ten minutes. Whether the issuer is the right
     * network key is the caller's question.
     */
    public static Certificate checkCertificate(byte[] bytes, long nowSeconds) throws Invalid {
        if (bytes.length < 86 + 64 || !Arrays.equals(bytes, 0, 4, CERTIFICATE_MAGIC, 0, 4)) {
            throw new Invalid("not a network certificate");
        }
        ByteBuffer view = ByteBuffer.wrap(bytes);
        view.position(4);
        byte[] issuer = new byte[RAW_BYTES];
        byte[] subject = new byte[RAW_BYTES];
        view.get(issuer).get(subject);
        long notBefore = view.getLong();
        long notAfter = view.getLong();
        int nameLength = view.get() & 0xFF;
        if (nameLength > 64 || bytes.length != 86 + nameLength + 64) {
            throw new Invalid("certificate has the wrong length");
        }
        byte[] name = new byte[nameLength];
        view.get(name);
        if (view.get() != 0) {
            throw new Invalid("certificate has flags this version does not know");
        }
        if (!verify(issuer, CERTIFICATE_CONTEXT, Arrays.copyOf(bytes, 86 + nameLength),
                Arrays.copyOfRange(bytes, 86 + nameLength, bytes.length))) {
            throw new Invalid("certificate is not signed by the key it names as issuer");
        }
        if (notBefore < 0 || notAfter > MAX_TIME || notBefore >= notAfter) {
            throw new Invalid("certificate validity is out of range or empty");
        }
        if (notAfter - notBefore > MAX_CERTIFICATE_SECONDS) {
            throw new Invalid("certificate is valid for longer than seven days");
        }
        if (nowSeconds < notBefore - CLOCK_TOLERANCE_SECONDS || nowSeconds > notAfter + CLOCK_TOLERANCE_SECONDS) {
            throw new Invalid("certificate is not valid now; check this server's clock");
        }
        return new Certificate(issuer, subject, notBefore, notAfter, new String(name, StandardCharsets.UTF_8));
    }

    // --- rotations -------------------------------------------------------------

    public record Rotation(int kind, byte[] oldKey, byte[] newKey, long issuedAt) {
    }

    /** Parses one statement and checks its signature by the old key. */
    public static Rotation parseRotation(byte[] bytes) throws Invalid {
        if (bytes.length != ROTATION_BYTES || !Arrays.equals(bytes, 0, 4, ROTATION_MAGIC, 0, 4)) {
            throw new Invalid("not a rotation statement");
        }
        int kind = bytes[4] & 0xFF;
        byte[] oldKey = Arrays.copyOfRange(bytes, 5, 37);
        byte[] newKey = Arrays.copyOfRange(bytes, 37, 69);
        long issuedAt = ByteBuffer.wrap(bytes, 69, 8).getLong();
        if (issuedAt < 0 || issuedAt > MAX_TIME) {
            throw new Invalid("rotation statement has an issue time out of range");
        }
        if (!verify(oldKey, ROTATION_CONTEXT, Arrays.copyOf(bytes, 77), Arrays.copyOfRange(bytes, 77, 141))) {
            throw new Invalid("rotation statement is not signed by the key it rotates from");
        }
        return new Rotation(kind, oldKey, newKey, issuedAt);
    }

    /**
     * Whether the statements (oldest first, each verified) lead from the key
     * whose fingerprint is {@code pinned} to {@code presented}, every one a
     * network rotation, as a GNLib client follows a fingerprint-only pin: the
     * chain starts at the first statement whose old key has that fingerprint.
     */
    public static boolean chainLeads(List<Rotation> statements, String pinned, byte[] presented) {
        int start = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (fingerprint(statements.get(i).oldKey()).equals(pinned)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return false;
        }
        byte[] current = statements.get(start).oldKey();
        for (int i = start; i < statements.size(); i++) {
            Rotation rotation = statements.get(i);
            if (rotation.kind() != KIND_NETWORK || !MessageDigest.isEqual(rotation.oldKey(), current)) {
                return false;
            }
            current = rotation.newKey();
        }
        return MessageDigest.isEqual(current, presented);
    }

    // --- keys --------------------------------------------------------------------

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no Ed25519", e);
        }
    }

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

    private static boolean verify(byte[] rawPublic, String context, byte[] message, byte[] signatureBytes) {
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

    private static byte[] sign(PrivateKey key, String context, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(context.getBytes(StandardCharsets.US_ASCII));
            signature.update((byte) 0);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign with the backend key", e);
        }
    }

    /** PKCS#8 PEM, as {@code openssl pkey} reads it. */
    public static String privatePem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
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
     * The raw public key of an Ed25519 private key: the JDK's generator run on
     * the key's own seed (RFC 8032 derives the pair from it), checked by a
     * signature with the stored key, so a wrong derivation refuses the key.
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
