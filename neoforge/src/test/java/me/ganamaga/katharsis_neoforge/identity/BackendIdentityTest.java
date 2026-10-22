package me.ganamaga.katharsis_neoforge.identity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendIdentityTest {

    private static final long NOW = 1_800_000_000L;
    private static final long LIFE = 48L * 3600L;

    @TempDir
    Path directory;

    private BackendIdentity load(String pin) throws Exception {
        return BackendIdentity.load(directory, pin, NOW);
    }

    private static byte[] certify(KeyPair network, BackendIdentity backend) throws Exception {
        return Signing.certificate(network, backend.rawPublic(), NOW - 300, NOW + LIFE, "lobby");
    }

    private static BackendIdentity.Outcome offer(BackendIdentity backend, byte[] certificate, List<byte[]> rotations)
            throws Exception {
        return backend.accept(true, "", certificate, rotations, NOW).outcome();
    }

    @Test
    @DisplayName("the key is made once, privately, and kept")
    void key() throws Exception {
        BackendIdentity first = load("");
        BackendIdentity second = load("");

        assertNull(first.problem());
        assertArrayEquals(first.rawPublic(), second.rawPublic());
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(directory.resolve(BackendIdentity.KEY_FILE)));
    }

    @Test
    @DisplayName("an unreadable key means no identity, and is left for the operator")
    void unreadableKey() throws Exception {
        Files.writeString(directory.resolve(BackendIdentity.KEY_FILE), "nope");

        BackendIdentity backend = load("");

        assertNotNull(backend.problem());
        assertFalse(backend.wantsCertificate(NOW));
        assertEquals("nope", Files.readString(directory.resolve(BackendIdentity.KEY_FILE)));
    }

    @Test
    @DisplayName("F8: without network-fingerprint nothing is trusted, and the proxy's fingerprint is reported")
    void noPin() throws Exception {
        KeyPair network = Signing.seeded(1);
        BackendIdentity backend = load("");

        BackendIdentity.Result result = backend.accept(true, "", certify(network, backend), List.of(), NOW);

        assertEquals(BackendIdentity.Outcome.NO_PIN, result.outcome());
        assertTrue(result.message().contains(IdentityFormatTest.NETWORK_FINGERPRINT), result.message());
        assertNull(backend.presented(NOW));
        assertFalse(Files.exists(directory.resolve(BackendIdentity.CERTIFICATE_FILE)));
    }

    @Test
    @DisplayName("a certificate from the pinned network is presented, kept across a restart, and renewed at half life")
    void pinned() throws Exception {
        KeyPair network = Signing.seeded(1);
        BackendIdentity backend = load(IdentityFormatTest.NETWORK_FINGERPRINT.toLowerCase());
        byte[] certificate = certify(network, backend);

        assertTrue(backend.wantsCertificate(NOW));
        assertEquals(BackendIdentity.Outcome.ACCEPTED, offer(backend, certificate, List.of()));
        BackendIdentity.Presented presented = backend.presented(NOW);
        assertArrayEquals(Signing.raw(network), presented.networkKey());
        assertArrayEquals(certificate, presented.certificate());
        assertFalse(backend.wantsCertificate(NOW));
        assertTrue(backend.wantsCertificate(NOW - 300 + (LIFE + 300) / 2));

        BackendIdentity restarted = load(IdentityFormatTest.NETWORK_FINGERPRINT);
        assertArrayEquals(certificate, restarted.presented(NOW).certificate());
        assertNull(restarted.presented(NOW + LIFE + 601), "nothing once it has run out");
    }

    @Test
    @DisplayName("F7: a certificate for another key, or from another network, is refused and the old one kept")
    void refusals() throws Exception {
        KeyPair network = Signing.seeded(1);
        BackendIdentity backend = load(IdentityFormatTest.NETWORK_FINGERPRINT);
        offer(backend, certify(network, backend), List.of());

        byte[] otherKey = Signing.certificate(network, Signing.raw(Signing.seeded(9)), NOW, NOW + LIFE, "lobby");
        assertEquals(BackendIdentity.Outcome.INVALID, offer(backend, otherKey, List.of()));
        assertEquals(BackendIdentity.Outcome.WRONG_NETWORK, offer(backend, certify(Signing.seeded(5), backend), List.of()));
        assertEquals(BackendIdentity.Outcome.REFUSED,
                backend.accept(false, "unknown_server", new byte[0], List.of(), NOW).outcome());
        assertArrayEquals(Signing.raw(network), backend.presented(NOW).networkKey());
    }

    @Test
    @DisplayName("F7: a rotation from the pinned key is followed, and the pin moves with it")
    void rotation() throws Exception {
        KeyPair network = Signing.seeded(1);
        KeyPair rotated = Signing.seeded(3);
        BackendIdentity backend = load(IdentityFormatTest.NETWORK_FINGERPRINT);
        byte[] statement = Signing.rotation(network, Signing.raw(rotated), NOW);

        BackendIdentity.Result result = backend.accept(true, "", certify(rotated, backend), List.of(statement), NOW);

        assertEquals(BackendIdentity.Outcome.REPINNED, result.outcome());
        assertEquals(IdentityFormat.fingerprint(Signing.raw(rotated)), result.pin());
        assertEquals(result.pin(), backend.pin());
        assertArrayEquals(Signing.raw(rotated), backend.presented(NOW).networkKey());
        assertArrayEquals(statement, backend.presented(NOW).rotations().getFirst(), "presented for pinned clients");

        BackendIdentity elsewhere = BackendIdentity.load(directory.resolve("other"),
                IdentityFormat.fingerprint(Signing.raw(Signing.seeded(5))), NOW);
        assertEquals(BackendIdentity.Outcome.WRONG_NETWORK,
                offer(elsewhere, certify(rotated, elsewhere), List.of(statement)), "only from the pinned key");
    }
}
