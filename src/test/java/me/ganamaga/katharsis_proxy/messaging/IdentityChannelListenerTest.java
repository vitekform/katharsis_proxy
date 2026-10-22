package me.ganamaga.katharsis_proxy.messaging;

import me.ganamaga.katharsis_proxy.identity.NetworkIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityChannelListenerTest {

    private static final long NOW = 1_800_000_000L;

    @TempDir
    Path data;

    private final IdentityChannelListener listener = new IdentityChannelListener(null,
            LoggerFactory.getLogger(IdentityChannelListenerTest.class), () -> null, Clock.systemUTC());

    private record Reply(String requestId, boolean success, String error, byte[] certificate, int rotations) {
    }

    private static Reply read(byte[] reply) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(reply));
        assertEquals(IdentityChannel.OP_CERT, in.readUTF());
        String requestId = in.readUTF();
        boolean success = in.readBoolean();
        String error = in.readUTF();
        if (!success) {
            return new Reply(requestId, false, error, new byte[0], 0);
        }
        byte[] certificate = new byte[in.readUnsignedShort()];
        in.readFully(certificate);
        return new Reply(requestId, true, error, certificate, in.readUnsignedByte());
    }

    private byte[] answer(String server, NetworkIdentity identity) {
        List<String> problems = new ArrayList<>();
        byte[] reply = listener.answer(HexFormat.of().parseHex(IdentityCodecTest.REQUEST), server, identity, NOW, problems);
        assertEquals(List.of(), problems);
        return reply;
    }

    @Test
    @DisplayName("a configured server gets a certificate for the key its connection carried, under its own name")
    void certifies() throws Exception {
        NetworkIdentity identity = NetworkIdentity.loadOrCreate(data.resolve("identity")).identity();

        Reply reply = read(answer("lobby", identity));

        assertTrue(reply.success());
        assertEquals("Katharsis-identity-1", reply.requestId());
        assertArrayEquals(identity.rawPublic(), java.util.Arrays.copyOfRange(reply.certificate(), 4, 36));
        assertArrayEquals(HexFormat.of().parseHex(IdentityCodecTest.KEY),
                java.util.Arrays.copyOfRange(reply.certificate(), 36, 68));
        assertEquals("lobby", new String(reply.certificate(), 85, 5, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, reply.rotations());
    }

    @Test
    @DisplayName("a server velocity.toml does not know, or a proxy without a key, gets no certificate")
    void refuses() throws Exception {
        NetworkIdentity identity = NetworkIdentity.loadOrCreate(data.resolve("identity")).identity();

        assertEquals(IdentityChannel.ERROR_UNKNOWN_SERVER, read(answer(null, identity)).error());
        assertEquals(IdentityChannel.ERROR_UNKNOWN_SERVER, read(answer("x".repeat(65), identity)).error());
        assertEquals(IdentityChannel.ERROR_NO_IDENTITY, read(answer("lobby", null)).error());
    }

    @Test
    @DisplayName("a malformed request is answered as such")
    void malformed() throws Exception {
        Reply reply = read(listener.answer(new byte[]{0, 1}, "lobby", null, NOW, new ArrayList<>()));

        assertEquals(IdentityChannel.ERROR_MALFORMED_REQUEST, reply.error());
    }
}
