package me.ganamaga.katharsis_proxy.messaging;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.ganamaga.katharsis_proxy.entity.Punishment;

import java.util.List;
import java.util.UUID;

/**
 * Encodes and decodes {@link PunishChannel} messages. Free of Velocity types,
 * so the wire format is tested on its own against the companion's codec.
 */
public final class PunishCodec {

    private PunishCodec() {
    }

    /**
     * A message from the companion.
     *
     * @param operation {@link PunishChannel#OP_MUTE_QUERY} or {@link PunishChannel#OP_MUTED_ATTEMPT}
     * @param uuid      the player it is about
     */
    public record Incoming(String operation, UUID uuid) {
    }

    /**
     * {@code mute_state}: the operation, the uuid, whether muted, and when
     * muted the end in epoch milliseconds ({@link PunishChannel#PERMANENT} for
     * never), the punishment's id, and the commands the mute blocks.
     *
     * @param mute the mute in force on the player's server, or {@code null} if none
     */
    public static byte[] encodeMuteState(UUID uuid, Punishment mute, List<String> blockedCommands) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(PunishChannel.OP_MUTE_STATE);
        out.writeUTF(uuid.toString());
        out.writeBoolean(mute != null);
        if (mute != null) {
            out.writeLong(mute.isPermanent() ? PunishChannel.PERMANENT : mute.expiresAt().toEpochMilli());
            out.writeLong(mute.id());
            out.writeInt(blockedCommands.size());
            for (String command : blockedCommands) {
                out.writeUTF(command);
            }
        }
        return out.toByteArray();
    }

    /**
     * Reads the operation and uuid every message starts with. Anything after
     * them is ignored, so a newer companion can add fields.
     *
     * @return the message, or {@code null} if it is not one
     */
    public static Incoming decode(byte[] data) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            String operation = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            return new Incoming(operation, uuid);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return null;
        }
    }
}
