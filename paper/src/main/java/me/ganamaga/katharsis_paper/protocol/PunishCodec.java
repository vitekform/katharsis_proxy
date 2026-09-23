package me.ganamaga.katharsis_paper.protocol;

import me.ganamaga.katharsis_paper.mute.MuteState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encodes and decodes {@link PunishChannel} messages. Free of Bukkit types so
 * the wire format can be tested without a running server.
 */
public final class PunishCodec {

    /** More commands than this in one state is not a message of ours. */
    private static final int MAX_COMMANDS = 1024;

    private PunishCodec() {
    }

    /**
     * A {@code mute_state} from the proxy.
     *
     * @param uuid  the player
     * @param state their mute here, or {@code null} if they are not muted
     */
    public record Update(UUID uuid, MuteState state) {
    }

    /** Asks the proxy for {@code uuid}'s mute state. */
    public static byte[] encodeQuery(UUID uuid) {
        return encodeHeader(PunishChannel.OP_MUTE_QUERY, uuid);
    }

    /** Tells the proxy {@code uuid} tried to speak while muted. */
    public static byte[] encodeAttempt(UUID uuid) {
        return encodeHeader(PunishChannel.OP_MUTED_ATTEMPT, uuid);
    }

    private static byte[] encodeHeader(String operation, UUID uuid) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(operation);
            out.writeUTF(uuid.toString());
        } catch (IOException e) {
            // A ByteArrayOutputStream cannot fail.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes a {@code mute_state}.
     *
     * @return the update, or {@code null} if the payload is not one
     */
    public static Update decodeState(byte[] message) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            if (!PunishChannel.OP_MUTE_STATE.equals(in.readUTF())) {
                return null;
            }
            UUID uuid = UUID.fromString(in.readUTF());
            if (!in.readBoolean()) {
                return new Update(uuid, null);
            }
            long expiresAt = in.readLong();
            long punishmentId = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > MAX_COMMANDS) {
                return null;
            }
            List<String> commands = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                commands.add(in.readUTF());
            }
            return new Update(uuid, new MuteState(expiresAt, punishmentId, commands));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
