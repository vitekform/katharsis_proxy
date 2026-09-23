package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * Wire constants for the {@code katharsis:punish} channel, over which the
 * proxy tells the companion plugin which players are muted on its server.
 *
 * <p>Every message starts with the operation and the player's uuid, each
 * written with {@code DataOutput#writeUTF}; {@code docs/plugin-messaging.md}
 * describes the fields after them.</p>
 */
public final class PunishChannel {

    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create("katharsis", "punish");

    /** Proxy to backend: whether the player is muted on this server, and what that blocks. */
    public static final String OP_MUTE_STATE = "mute_state";

    /** Backend to proxy: send me this player's {@link #OP_MUTE_STATE}. */
    public static final String OP_MUTE_QUERY = "mute_query";

    /** Backend to proxy: a muted player tried to speak; tell them why they cannot. */
    public static final String OP_MUTED_ATTEMPT = "muted_attempt";

    /** Written in a {@code mute_state} for a mute that never ends. */
    public static final long PERMANENT = -1L;

    private PunishChannel() {
    }
}
