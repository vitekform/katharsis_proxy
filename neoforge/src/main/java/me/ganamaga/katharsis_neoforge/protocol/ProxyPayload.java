package me.ganamaga.katharsis_neoforge.protocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * A message on one of the channels this mod shares with the proxy's companion
 * protocol — {@code katharsis:language}, {@code katharsis:chat} and
 * {@code katharsis:punish} — carried as the raw bytes the Paper companion sends
 * and receives. The codecs in this package read and write them.
 *
 * <p>Registered like {@link AuthPayload}, and for the same reasons: optional,
 * so clients without the mod can join and the channel never needs negotiating,
 * and with a handler that is never called, because {@code Inbox} takes these
 * off the connection before NeoForge sees them.</p>
 */
public record ProxyPayload(CustomPacketPayload.Type<ProxyPayload> type, byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ProxyPayload> LANGUAGE = new CustomPacketPayload.Type<>(Ids.LANGUAGE);
    public static final CustomPacketPayload.Type<ProxyPayload> CHAT = new CustomPacketPayload.Type<>(Ids.CHAT);
    public static final CustomPacketPayload.Type<ProxyPayload> PUNISH = new CustomPacketPayload.Type<>(Ids.PUNISH);

    /** The version these channels are registered under; the wire formats carry none of their own. */
    private static final String PROTOCOL = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
        for (CustomPacketPayload.Type<ProxyPayload> type : List.of(LANGUAGE, CHAT, PUNISH)) {
            registrar.playBidirectional(type, codec(type), (payload, context) -> {
            });
        }
    }

    private static StreamCodec<FriendlyByteBuf, ProxyPayload> codec(CustomPacketPayload.Type<ProxyPayload> type) {
        return StreamCodec.of(
                (buffer, payload) -> buffer.writeBytes(payload.data),
                buffer -> {
                    byte[] data = new byte[buffer.readableBytes()];
                    buffer.readBytes(data);
                    return new ProxyPayload(type, data);
                });
    }
}
