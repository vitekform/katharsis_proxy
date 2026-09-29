package me.ganamaga.katharsis_proxy.messaging;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Encodes and decodes {@link AuthChannel} messages. Free of Velocity types,
 * so the wire format is tested on its own against the mod's codec.
 */
public final class AuthCodec {

    private AuthCodec() {
    }

    /**
     * A message from the mod.
     *
     * @param operation {@link AuthChannel#OP_LOCKED}
     * @param uuid      the player it is about
     * @param protocol  the mod's {@link AuthChannel#PROTOCOL}, or empty if it sent none
     */
    public record Incoming(String operation, UUID uuid, String protocol) {
    }

    /** {@code unlock}: the operation and the uuid. */
    public static byte[] encodeUnlock(UUID uuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(AuthChannel.OP_UNLOCK);
        out.writeUTF(uuid.toString());
        return out.toByteArray();
    }

    /**
     * Reads the operation, the uuid and, when present, the protocol. Anything
     * after them is ignored, so a newer mod can add fields.
     *
     * @return the message, or {@code null} if it is not one
     */
    public static Incoming decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String operation = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            String protocol = in.available() > 0 ? in.readUTF() : "";
            return new Incoming(operation, uuid, protocol);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
