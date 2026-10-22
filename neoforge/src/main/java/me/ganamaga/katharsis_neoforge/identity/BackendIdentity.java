package me.ganamaga.katharsis_neoforge.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This backend's part of the network's GNLib identity: its own Ed25519 key, the
 * certificate the proxy issued for it, and the network key it trusts.
 *
 * <p>GNLib clients pin the proxy's network key, not this server's, so moving
 * between backends keeps one identity; each backend signs its handshakes with
 * its own key and presents the certificate that binds it to the network.</p>
 *
 * <p>The network key is pinned here too, by {@code network-fingerprint} in the
 * companion's settings, and every certificate is checked against it: one forged
 * by someone who reached this server directly is useless. Without a pinned
 * fingerprint there is no network identity at all - no trust-the-first-one -
 * and the proxy's fingerprint is reported for the operator to check and paste
 * in. A certificate from another network key is taken only with rotation
 * statements leading to it from the pinned one, and the pin follows.</p>
 *
 * <p>Free of Minecraft and GNLib types. Its public methods are synchronized:
 * replies arrive on the server thread, and GNLib asks for {@link #presented}
 * from wherever it sets up a connection.</p>
 */
public final class BackendIdentity {

    public static final String KEY_FILE = "backend.key";
    public static final String CERTIFICATE_FILE = "backend.cert";
    public static final String ROTATIONS_FILE = "backend.rotations";

    /** What GNLib presents: the network key clients pin, and this backend's key and certificate. */
    public record Presented(byte[] networkKey, PrivateKey backendKey, byte[] certificate, List<byte[]> rotations) {
    }

    /** What became of a certificate from the proxy. */
    public enum Outcome {
        /** Issued by the pinned network key, and in use. */
        ACCEPTED,
        /** Issued by a new network key that rotation statements lead to from the pinned one; the pin moved. */
        REPINNED,
        /** No {@code network-fingerprint} is set, so nothing is trusted; the proxy's fingerprint is in the message. */
        NO_PIN,
        /** Issued by a network key that is not the pinned one, with no rotation leading to it. */
        WRONG_NETWORK,
        /** Malformed, not for this backend's key, or not valid now. */
        INVALID,
        /** The proxy answered with an error. */
        REFUSED
    }

    /** @param pin the pinned fingerprint after this, which differs from before only on {@link Outcome#REPINNED} */
    public record Result(Outcome outcome, String message, String pin) {
    }

    private final Path directory;
    /** Null when {@link #KEY_FILE} exists but is unusable. */
    private final KeyPair pair;
    private final byte[] rawPublic;
    private final String problem;

    /** The pinned network fingerprint, in display form, or null when none is set. */
    private String pin;
    private byte[] certificate;
    private IdentityFormat.Certificate parsed;
    private List<byte[]> rotations = List.of();

    private BackendIdentity(Path directory, KeyPair pair, byte[] rawPublic, String problem, String pin) {
        this.directory = directory;
        this.pair = pair;
        this.rawPublic = rawPublic;
        this.problem = problem;
        this.pin = pin;
    }

    /**
     * Loads this backend's key from {@code directory}, generating one if there
     * is none, and the certificate kept from last time if it still holds.
     * Never silently replaces an unreadable key: a new one would need a new
     * certificate anyway, but an unreadable file is a problem an operator should
     * see, so there is no identity until it is fixed.
     *
     * @param pinned {@code network-fingerprint} as configured; blank or malformed means none
     */
    public static BackendIdentity load(Path directory, String pinned, long nowSeconds) throws IOException {
        String pin = pinned == null || pinned.isBlank() ? null : IdentityFormat.normalise(pinned);
        Path keyFile = directory.resolve(KEY_FILE);
        if (Files.exists(keyFile)) {
            try {
                KeyPair pair = IdentityFormat.readPrivatePem(Files.readString(keyFile, StandardCharsets.US_ASCII));
                BackendIdentity identity = new BackendIdentity(directory, pair, IdentityFormat.raw(pair.getPublic()), null, pin);
                identity.loadCertificate(nowSeconds);
                return identity;
            } catch (IdentityFormat.Invalid | IOException e) {
                return new BackendIdentity(directory, null, null, keyFile + " exists but cannot be used ("
                        + e.getMessage() + "); restore it or delete it to make a new one", pin);
            }
        }
        createPrivateDirectory(directory);
        KeyPair pair = IdentityFormat.generate();
        writeAtomically(keyFile, IdentityFormat.privatePem(pair.getPrivate()).getBytes(StandardCharsets.US_ASCII), true);
        try {
            return new BackendIdentity(directory, pair, IdentityFormat.raw(pair.getPublic()), null, pin);
        } catch (IdentityFormat.Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    private void loadCertificate(long nowSeconds) throws IOException {
        Path certificateFile = directory.resolve(CERTIFICATE_FILE);
        if (!Files.exists(certificateFile)) {
            return;
        }
        byte[] stored = Files.readAllBytes(certificateFile);
        byte[] storedRotations = Files.exists(directory.resolve(ROTATIONS_FILE))
                ? Files.readAllBytes(directory.resolve(ROTATIONS_FILE)) : new byte[0];
        if (storedRotations.length % IdentityFormat.ROTATION_BYTES != 0) {
            return;
        }
        List<byte[]> statements = new ArrayList<>();
        for (int at = 0; at < storedRotations.length; at += IdentityFormat.ROTATION_BYTES) {
            statements.add(Arrays.copyOfRange(storedRotations, at, at + IdentityFormat.ROTATION_BYTES));
        }
        try {
            IdentityFormat.Certificate checked = check(stored, nowSeconds);
            if (pin != null && IdentityFormat.fingerprint(checked.issuer()).equals(pin)) {
                use(stored, checked, statements);
            }
        } catch (IdentityFormat.Invalid e) {
            // Expired or for an older key: a new one is asked for.
        }
    }

    /** Why there is no backend key, or null when there is one. */
    public String problem() {
        return problem;
    }

    /** This backend's raw public key, which the proxy certifies; null when there is no key. */
    public byte[] rawPublic() {
        return rawPublic == null ? null : rawPublic.clone();
    }

    public String fingerprint() {
        return rawPublic == null ? "none" : IdentityFormat.fingerprint(rawPublic);
    }

    /** The pinned network fingerprint, or null. */
    public synchronized String pin() {
        return pin;
    }

    /** The network key's fingerprint from the certificate in use, or null. */
    public synchronized String networkFingerprint() {
        return parsed == null ? null : IdentityFormat.fingerprint(parsed.issuer());
    }

    /** What to present to a GNLib client now, or null for none (GNLib then closes with NO_IDENTITY). */
    public synchronized Presented presented(long nowSeconds) {
        if (pair == null || parsed == null || pin == null
                || nowSeconds > parsed.notAfter() + IdentityFormat.CLOCK_TOLERANCE_SECONDS) {
            return null;
        }
        return new Presented(parsed.issuer().clone(), pair.getPrivate(), certificate.clone(),
                rotations.stream().map(byte[]::clone).toList());
    }

    /** Whether to ask the proxy for a certificate: there is none, or the one in use is past half its life. */
    public synchronized boolean wantsCertificate(long nowSeconds) {
        if (pair == null) {
            return false;
        }
        return parsed == null || nowSeconds >= parsed.notBefore() + (parsed.notAfter() - parsed.notBefore()) / 2;
    }

    /**
     * Takes the proxy's answer to a request.
     *
     * @param success whether the proxy answered with a certificate
     * @param error   the proxy's error code when it did not
     */
    public synchronized Result accept(boolean success, String error, byte[] offered, List<byte[]> offeredRotations,
                         long nowSeconds) throws IOException {
        if (!success) {
            return new Result(Outcome.REFUSED, "the proxy did not certify this server's key (" + error + ")", pin);
        }
        IdentityFormat.Certificate checked;
        try {
            checked = check(offered, nowSeconds);
        } catch (IdentityFormat.Invalid e) {
            return new Result(Outcome.INVALID, "the proxy's certificate was refused: " + e.getMessage(), pin);
        }
        String issuer = IdentityFormat.fingerprint(checked.issuer());
        if (pin == null) {
            return new Result(Outcome.NO_PIN, "network-fingerprint is not set, so this server presents no network "
                    + "identity and GNLib players get no remote content here. The proxy presented " + issuer
                    + "; check it against /kidentity on the proxy and set network-fingerprint=" + issuer, null);
        }
        if (issuer.equals(pin)) {
            use(offered, checked, offeredRotations);
            store();
            return new Result(Outcome.ACCEPTED, "certified as '" + checked.name() + "' by the network " + issuer
                    + " until " + java.time.Instant.ofEpochSecond(checked.notAfter()), pin);
        }
        List<IdentityFormat.Rotation> statements = new ArrayList<>();
        for (byte[] rotation : offeredRotations) {
            try {
                statements.add(IdentityFormat.parseRotation(rotation));
            } catch (IdentityFormat.Invalid e) {
                return new Result(Outcome.WRONG_NETWORK, "the proxy's certificate is from the network key " + issuer
                        + ", not the pinned " + pin + ", and a rotation statement with it is broken: "
                        + e.getMessage(), pin);
            }
        }
        if (!IdentityFormat.chainLeads(statements, pin, checked.issuer())) {
            return new Result(Outcome.WRONG_NETWORK, "the proxy's certificate is from the network key " + issuer
                    + ", not the pinned " + pin + ", and no rotation leads from one to the other. If the network "
                    + "key really changed without a rotation, check the new fingerprint with /kidentity on the "
                    + "proxy and set network-fingerprint", pin);
        }
        String previous = pin;
        pin = issuer;
        use(offered, checked, offeredRotations);
        store();
        return new Result(Outcome.REPINNED, "the network key rotated from " + previous + " to " + issuer
                + "; network-fingerprint follows it", pin);
    }

    /** As a GNLib client checks it, and for this backend's own key (crypto review F7). */
    private IdentityFormat.Certificate check(byte[] offered, long nowSeconds) throws IdentityFormat.Invalid {
        IdentityFormat.Certificate checked = IdentityFormat.checkCertificate(offered, nowSeconds);
        if (rawPublic == null || !java.security.MessageDigest.isEqual(checked.subject(), rawPublic)) {
            throw new IdentityFormat.Invalid("it certifies another key than this server's");
        }
        return checked;
    }

    private void use(byte[] offered, IdentityFormat.Certificate checked, List<byte[]> offeredRotations) {
        certificate = offered.clone();
        parsed = checked;
        List<byte[]> kept = offeredRotations.stream().map(byte[]::clone).toList();
        rotations = kept.subList(Math.max(0, kept.size() - IdentityFormat.MAX_ROTATIONS), kept.size());
    }

    /** Kept on disk, so a restart can present it without waiting for the proxy. */
    private void store() throws IOException {
        writeAtomically(directory.resolve(CERTIFICATE_FILE), certificate, false);
        byte[] all = new byte[rotations.size() * IdentityFormat.ROTATION_BYTES];
        for (int i = 0; i < rotations.size(); i++) {
            System.arraycopy(rotations.get(i), 0, all, i * IdentityFormat.ROTATION_BYTES, IdentityFormat.ROTATION_BYTES);
        }
        writeAtomically(directory.resolve(ROTATIONS_FILE), all, false);
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        Files.createDirectories(directory.toAbsolutePath().getParent());
        if (posix()) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(directory);
        }
    }

    /** A temporary file renamed over the target; a secret is {@code 0600} before its bytes go in. */
    static void writeAtomically(Path target, byte[] bytes, boolean secret) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        if (secret && posix()) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
