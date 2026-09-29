package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * Wire constants for the {@code katharsis:auth} channel, over which the
 * Katharsis NeoForge mod holds each player in place until the proxy says they
 * have logged in.
 *
 * <p>Every message is the operation and the player's uuid, each written with
 * {@code DataOutput#writeUTF}; {@code docs/plugin-messaging.md} describes the
 * fields after them.</p>
 */
public final class AuthChannel {

    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create("katharsis", "auth");

    /**
     * Backend to proxy: the player has joined and cannot do anything until
     * {@link #OP_UNLOCK}. Followed by the mod's {@link #PROTOCOL}.
     */
    public static final String OP_LOCKED = "locked";

    /** Proxy to backend: the player has logged in; let them play. */
    public static final String OP_UNLOCK = "unlock";

    /** Raised only when a change would be misread by the other side. */
    public static final String PROTOCOL = "1";

    private AuthChannel() {
    }
}
