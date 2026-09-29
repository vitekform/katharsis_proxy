package me.ganamaga.katharsis_neoforge.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * The {@code katharsis:auth} wire format, shared with the proxy's
 * {@code AuthCodec}: the operation and the player's uuid, each written with
 * {@code DataOutput#writeUTF}, and on {@code locked} this mod's protocol.
 */
public final class AuthCodec {

    /** Mod to proxy: the player has joined and is held in place until unlocked. */
    public static final String OP_LOCKED = "locked";

    /** Proxy to mod: the player has logged in; let them play. */
    public static final String OP_UNLOCK = "unlock";

    /** Raised only when a change would be misread by the other side. */
    public static final String PROTOCOL = "1";

    private AuthCodec() {
    }

    /** A message from the proxy. */
    public record Incoming(String operation, UUID uuid) {
    }

    public static byte[] encodeLocked(UUID uuid) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(OP_LOCKED);
            out.writeUTF(uuid.toString());
            out.writeUTF(PROTOCOL);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // not thrown by an in-memory stream
        }
        return bytes.toByteArray();
    }

    /**
     * Reads the operation and uuid. Anything after them is ignored, so a newer
     * proxy can add fields.
     *
     * @return the message, or {@code null} if it is not one
     */
    public static Incoming decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return new Incoming(in.readUTF(), UUID.fromString(in.readUTF()));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
