package me.ganamaga.katharsis_neoforge.protocol;

/**
 * Wire constants for the {@code katharsis:punish} channel.
 *
 * <p>This mirrors the proxy plugin's class of the same name, for the same
 * reason {@link LanguageChannel} does; {@code docs/plugin-messaging.md} in the
 * proxy repository is the contract both sides implement.</p>
 */
public final class PunishChannel {

    /** The channel mute states and questions about them travel on. */
    public static final String CHANNEL = "katharsis:punish";

    /** Proxy to backend: whether a player is muted here, and what that blocks. */
    public static final String OP_MUTE_STATE = "mute_state";

    /** Backend to proxy: send this player's {@link #OP_MUTE_STATE}. */
    public static final String OP_MUTE_QUERY = "mute_query";

    /** Backend to proxy: a muted player tried to speak. */
    public static final String OP_MUTED_ATTEMPT = "muted_attempt";

    /** The end of a mute that never ends. */
    public static final long PERMANENT = -1L;

    private PunishChannel() {
    }
}
