package me.ganamaga.katharsis_neoforge.protocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * A {@code katharsis:auth} message, carried as the raw bytes the proxy's plugin
 * messages are made of. {@link AuthCodec} reads and writes them.
 */
public record AuthPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AuthPayload> TYPE = new CustomPacketPayload.Type<>(Ids.AUTH);

    private static final StreamCodec<FriendlyByteBuf, AuthPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data),
            buffer -> {
                byte[] data = new byte[buffer.readableBytes()];
                buffer.readBytes(data);
                return new AuthPayload(data);
            });

    /**
     * Optional, so clients, which never have this mod, can still join. That
     * also lets the proxy's messages be read without the channel having been
     * negotiated with the client, which it never is.
     *
     * <p>No handler is ever called: the lock takes these messages off the
     * connection before NeoForge sees them.</p>
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(AuthCodec.PROTOCOL).optional()
                .playBidirectional(TYPE, CODEC, (payload, context) -> {
                });
    }

    @Override
    public CustomPacketPayload.Type<AuthPayload> type() {
        return TYPE;
    }
}
