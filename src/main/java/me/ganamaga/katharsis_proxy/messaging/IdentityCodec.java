package me.ganamaga.katharsis_proxy.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Encodes and decodes {@link IdentityChannel} messages. Free of Velocity types,
 * so the wire format is tested on its own against the mod's codec.
 *
 * <p>A request is the operation, the request id and the mod's protocol, each
 * written with {@code DataOutput#writeUTF}, then the backend's raw 32-byte
 * Ed25519 key. A reply is the operation, the request id, a {@code success}
 * byte and an error code; on success the certificate follows, with an unsigned
 * 16-bit length, then a byte counting the 141-byte rotation statements after
 * it.</p>
 */
public final class IdentityCodec {

    public static final int KEY_BYTES = 32;
    public static final int ROTATION_BYTES = 141;

    private IdentityCodec() {
    }

    /**
     * A {@code cert_request}.
     *
     * @param protocol the mod's {@link IdentityChannel#PROTOCOL}
     * @param key      the backend's raw public key
     */
    public record Request(String operation, String requestId, String protocol, byte[] key) {
    }

    /** @return the request, or {@code null} if it is not one */
    public static Request decodeRequest(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String operation = in.readUTF();
            String requestId = in.readUTF();
            String protocol = in.readUTF();
            byte[] key = new byte[KEY_BYTES];
            in.readFully(key);
            return new Request(operation, requestId, protocol, key);
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] encodeCertificate(String requestId, byte[] certificate, List<byte[]> rotations) {
        if (certificate.length > 0xFFFF || rotations.size() > 0xFF) {
            throw new IllegalArgumentException("certificate or rotation list too long");
        }
        return write(out -> {
            out.writeUTF(IdentityChannel.OP_CERT);
            out.writeUTF(requestId);
            out.writeBoolean(true);
            out.writeUTF(IdentityChannel.ERROR_NONE);
            out.writeShort(certificate.length);
            out.write(certificate);
            out.writeByte(rotations.size());
            for (byte[] rotation : rotations) {
                if (rotation.length != ROTATION_BYTES) {
                    throw new IllegalArgumentException("a rotation statement is " + ROTATION_BYTES + " bytes");
                }
                out.write(rotation);
            }
        });
    }

    public static byte[] encodeFailure(String requestId, String error) {
        return write(out -> {
            out.writeUTF(IdentityChannel.OP_CERT);
            out.writeUTF(requestId);
            out.writeBoolean(false);
            out.writeUTF(error);
        });
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // not thrown by an in-memory stream
        }
        return bytes.toByteArray();
    }
}
