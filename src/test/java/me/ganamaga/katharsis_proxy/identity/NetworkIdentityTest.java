package me.ganamaga.katharsis_proxy.identity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkIdentityTest {

    private static final long NOW = 1_800_000_000L;

    @TempDir
    Path data;

    @Test
    @DisplayName("generated once, then loaded, with the operator's files beside it")
    void generatesThenLoads() throws Exception {
        Path directory = data.resolve("identity");
        NetworkIdentity.Loaded first = NetworkIdentity.loadOrCreate(directory);
        NetworkIdentity.Loaded second = NetworkIdentity.loadOrCreate(directory);

        assertTrue(first.generated());
        assertFalse(second.generated());
        assertArrayEquals(first.identity().rawPublic(), second.identity().rawPublic());
        assertEquals(first.identity().fingerprint() + "\n", Files.readString(directory.resolve("fingerprint.txt")));
        assertTrue(Files.readString(directory.resolve("network.pub")).startsWith("-----BEGIN PUBLIC KEY-----"));
    }

    @Test
    @DisplayName("the key and its directory are private on POSIX")
    void privateFiles() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path directory = data.resolve("identity");
        NetworkIdentity.loadOrCreate(directory);

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(directory.resolve(NetworkIdentity.KEY_FILE)));
    }

    @Test
    @DisplayName("an unreadable key is never replaced")
    void neverRegenerates() throws Exception {
        Path directory = Files.createDirectories(data.resolve("identity"));
        Files.writeString(directory.resolve(NetworkIdentity.KEY_FILE), "not a key");

        assertThrows(NetworkIdentity.Unusable.class, () -> NetworkIdentity.loadOrCreate(directory));
        assertEquals("not a key", Files.readString(directory.resolve(NetworkIdentity.KEY_FILE)));
    }

    @Test
    @DisplayName("a certificate names the server, covers 48 hours and starts a little early")
    void certifies() throws Exception {
        NetworkIdentity identity = NetworkIdentity.loadOrCreate(data.resolve("identity")).identity();
        byte[] backend = IdentityFormat.raw(IdentityFormat.generate().getPublic());

        byte[] certificate = identity.certify(backend, "lobby", NOW);

        assertEquals(86 + 5 + 64, certificate.length);
        java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(certificate);
        assertEquals(NOW - NetworkIdentity.BACKDATE_SECONDS, view.getLong(68));
        assertEquals(NOW + NetworkIdentity.CERTIFICATE_SECONDS, view.getLong(76));
        assertEquals("lobby", new String(certificate, 85, 5, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("rotating writes a statement from the old key, retires it and replaces the private key")
    void rotates() throws Exception {
        Path directory = data.resolve("identity");
        NetworkIdentity old = NetworkIdentity.loadOrCreate(directory).identity();

        NetworkIdentity rotated = old.rotate(NOW);
        List<String> problems = new ArrayList<>();
        List<byte[]> statements = rotated.rotations(NOW + 60, problems);

        assertNotEquals(old.fingerprint(), rotated.fingerprint());
        assertEquals(List.of(), problems);
        assertEquals(1, statements.size());
        assertArrayEquals(old.rawPublic(), java.util.Arrays.copyOfRange(statements.getFirst(), 5, 37));
        assertArrayEquals(rotated.rawPublic(), java.util.Arrays.copyOfRange(statements.getFirst(), 37, 69));
        assertTrue(Files.exists(directory.resolve("retired").resolve(old.fingerprint().replace(':', '_') + ".pub")));
        assertEquals(rotated.fingerprint(), NetworkIdentity.loadOrCreate(directory).identity().fingerprint());
        assertEquals(List.of(), rotated.rotations(NOW + NetworkIdentity.ROTATION_GRACE_SECONDS + 1, problems),
                "a statement past its grace period is no longer handed out");
    }
}
