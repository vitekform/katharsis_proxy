package me.ganamaga.katharsis_neoforge.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code katharsis:identity} wire format, shared with the proxy's
 * {@code IdentityCodec}: a request is the operation, request id and this mod's
 * protocol, each written with {@code DataOutput#writeUTF}, then the backend's
 * raw 32-byte key. A reply is the operation, the request id, a {@code success}
 * byte and an error code; on success the certificate with an unsigned 16-bit
 * length, then a byte counting the 141-byte rotation statements after it.
 */
public final class IdentityCodec {

    public static final String CHANNEL = "katharsis:identity";

    /** Mod to proxy: certify this key. */
    public static final String OP_CERT_REQUEST = "cert_request";

    /** Proxy to mod: the certificate and the network's rotation statements. */
    public static final String OP_CERT = "cert";

    /** Raised only when a change would be misread by the other side. */
    public static final String PROTOCOL = "1";

    public static final int KEY_BYTES = 32;
    public static final int ROTATION_BYTES = 141;

    private IdentityCodec() {
    }

    /**
     * A reply from the proxy.
     *
     * @param error       empty on success
     * @param certificate empty on failure
     * @param rotations   oldest first; empty on failure
     */
    public record Reply(String requestId, boolean success, String error, byte[] certificate, List<byte[]> rotations) {
    }

    public static byte[] encodeRequest(String requestId, byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("a backend key is " + KEY_BYTES + " bytes");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(OP_CERT_REQUEST);
            out.writeUTF(requestId);
            out.writeUTF(PROTOCOL);
            out.write(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // not thrown by an in-memory stream
        }
        return bytes.toByteArray();
    }

    /** @return the reply, or {@code null} if it is not one */
    public static Reply decodeReply(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!OP_CERT.equals(in.readUTF())) {
                return null;
            }
            String requestId = in.readUTF();
            boolean success = in.readBoolean();
            String error = in.readUTF();
            if (!success) {
                return new Reply(requestId, false, error, new byte[0], List.of());
            }
            byte[] certificate = new byte[in.readUnsignedShort()];
            in.readFully(certificate);
            int count = in.readUnsignedByte();
            List<byte[]> rotations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] rotation = new byte[ROTATION_BYTES];
                in.readFully(rotation);
                rotations.add(rotation);
            }
            return new Reply(requestId, true, error, certificate, List.copyOf(rotations));
        } catch (IOException e) {
            return null;
        }
    }
}
