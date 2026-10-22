package me.ganamaga.katharsis_proxy.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The network's GNLib identity: the Ed25519 key GNLib clients pin for every
 * address of this proxy, and that vouches for each backend's own key with a
 * short-lived certificate.
 *
 * <p>Kept in {@code identity/} under the plugin's data directory, laid out as
 * GNLib keeps a server's identity: {@value #KEY_FILE} (PKCS#8 PEM, the only file
 * read back), {@code network.pub} and {@code fingerprint.txt} for operators,
 * {@code rotations/<n>.bin} and {@code retired/}. The directory is {@code 0700}
 * and the key {@code 0600} where the file system has POSIX permissions.</p>
 *
 * <p>Never silently regenerated: a {@value #KEY_FILE} that exists but cannot be
 * read means no identity at all, because a new key would make every GNLib player
 * who has joined refuse the network.</p>
 */
public final class NetworkIdentity {

    public static final String KEY_FILE = "network.key";

    /** How long a certificate the proxy issues is valid (identity.md, "Network certificates"). */
    public static final long CERTIFICATE_SECONDS = 48L * 60L * 60L;

    /** How far back a certificate's validity starts, for a backend whose clock is a little behind. */
    public static final long BACKDATE_SECONDS = 5L * 60L;

    /** Rotation statements are presented for this long after they are issued. */
    public static final long ROTATION_GRACE_SECONDS = 180L * 24L * 60L * 60L;

    private final Path directory;
    private final KeyPair pair;
    private final byte[] rawPublic;

    private NetworkIdentity(Path directory, KeyPair pair, byte[] rawPublic) {
        this.directory = directory;
        this.pair = pair;
        this.rawPublic = rawPublic;
    }

    /** {@value #KEY_FILE} is there but unusable; nothing is written over it. */
    public static final class Unusable extends Exception {
        private static final long serialVersionUID = 1L;

        public Unusable(String message) {
            super(message);
        }
    }

    /** @param generated whether the key was made just now */
    public record Loaded(NetworkIdentity identity, boolean generated) {
    }

    /** Loads the key in {@code directory}, generating one if there is no {@value #KEY_FILE}. */
    public static Loaded loadOrCreate(Path directory) throws IOException, Unusable {
        Path keyFile = directory.resolve(KEY_FILE);
        if (Files.exists(keyFile)) {
            KeyPair pair;
            try {
                pair = IdentityFormat.readPrivatePem(Files.readString(keyFile, StandardCharsets.US_ASCII));
            } catch (IdentityFormat.Invalid | IOException e) {
                throw new Unusable(keyFile + " exists but cannot be used (" + e.getMessage() + "). It is not "
                        + "replaced, since a new key would make every GNLib player who has joined refuse this "
                        + "network; restore it from a backup, or delete it to start over with a new identity");
            }
            return new Loaded(new NetworkIdentity(directory, pair, raw(pair)), false);
        }
        createPrivateDirectory(directory);
        KeyPair pair = IdentityFormat.generate();
        NetworkIdentity identity = new NetworkIdentity(directory, pair, raw(pair));
        identity.writeKeyFiles();
        return new Loaded(identity, true);
    }

    public byte[] rawPublic() {
        return rawPublic.clone();
    }

    public String fingerprint() {
        return IdentityFormat.fingerprint(rawPublic);
    }

    public Path directory() {
        return directory;
    }

    /**
     * A certificate for a backend's key, named as velocity.toml names the
     * server, valid from a few minutes ago for {@link #CERTIFICATE_SECONDS}.
     */
    public byte[] certify(byte[] backendKey, String serverName, long nowSeconds) {
        return IdentityFormat.certificate(pair.getPrivate(), rawPublic, backendKey,
                nowSeconds - BACKDATE_SECONDS, nowSeconds + CERTIFICATE_SECONDS, serverName);
    }

    /**
     * The rotation statements to hand out with certificates: every one issued
     * within the grace period, oldest first, at most
     * {@link IdentityFormat#MAX_ROTATIONS} (the newest). A file that does not
     * parse is skipped and named in {@code problems}.
     */
    public List<byte[]> rotations(long nowSeconds, List<String> problems) {
        Path rotations = directory.resolve("rotations");
        if (!Files.isDirectory(rotations)) {
            return List.of();
        }
        record Found(long issuedAt, byte[] bytes) {
        }
        List<Found> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(rotations)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("\\d+\\.bin")).toList()) {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    long issuedAt = IdentityFormat.rotationIssuedAt(bytes);
                    if (nowSeconds - issuedAt <= ROTATION_GRACE_SECONDS) {
                        found.add(new Found(issuedAt, bytes));
                    }
                } catch (IdentityFormat.Invalid | IOException e) {
                    problems.add(file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add(rotations + ": " + e.getMessage());
        }
        found.sort(Comparator.comparingLong(Found::issuedAt));
        return found.subList(Math.max(0, found.size() - IdentityFormat.MAX_ROTATIONS), found.size()).stream()
                .map(Found::bytes)
                .toList();
    }

    /**
     * Changes the network key: a new pair, a statement signed by the old key as
     * the next {@code rotations/<n>.bin}, the old public key to {@code retired/},
     * and the old private key gone. Kept, it would still speak for the network to
     * every client pinned to it, and could sign a competing rotation.
     *
     * @return the new identity
     */
    public NetworkIdentity rotate(long nowSeconds) throws IOException {
        KeyPair next = IdentityFormat.generate();
        byte[] statement = IdentityFormat.rotation(pair.getPrivate(), rawPublic, raw(next), nowSeconds);
        Path rotations = directory.resolve("rotations");
        Files.createDirectories(rotations);
        int number = 1;
        while (Files.exists(rotations.resolve(number + ".bin"))) {
            number++;
        }
        writeAtomically(rotations.resolve(number + ".bin"), statement, false);
        Path retired = directory.resolve("retired");
        Files.createDirectories(retired);
        writeAtomically(retired.resolve(fingerprint().replace(':', '_') + ".pub"),
                IdentityFormat.publicPem(pair.getPublic()).getBytes(StandardCharsets.US_ASCII), false);
        NetworkIdentity rotated = new NetworkIdentity(directory, next, raw(next));
        // The rename over network.key is what deletes the old private key.
        rotated.writeKeyFiles();
        return rotated;
    }

    private void writeKeyFiles() throws IOException {
        writeAtomically(directory.resolve(KEY_FILE),
                IdentityFormat.privatePem(pair.getPrivate()).getBytes(StandardCharsets.US_ASCII), true);
        writeAtomically(directory.resolve("network.pub"),
                IdentityFormat.publicPem(pair.getPublic()).getBytes(StandardCharsets.US_ASCII), false);
        writeAtomically(directory.resolve("fingerprint.txt"),
                (fingerprint() + "\n").getBytes(StandardCharsets.US_ASCII), false);
    }

    private static byte[] raw(KeyPair pair) {
        try {
            return IdentityFormat.raw(pair.getPublic());
        } catch (IdentityFormat.Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** {@code 0700} on POSIX, created before anything is written into it. */
    private static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        Path parent = directory.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (posix()) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(directory);
        }
    }

    /**
     * Writes a temporary file and renames it over the target, so a crash never
     * leaves half a key. A secret is created {@code 0600} before its bytes go in.
     */
    static void writeAtomically(Path target, byte[] bytes, boolean secret) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        if (secret && posix()) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, bytes);
        } else {
            Files.write(temporary, bytes);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
