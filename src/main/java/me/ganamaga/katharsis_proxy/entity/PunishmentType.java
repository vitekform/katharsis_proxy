package me.ganamaga.katharsis_proxy.entity;

/**
 * What a {@link Punishment} does to its target.
 */
public enum PunishmentType {

    /** Keeps the target off the scope's servers, or off the proxy when global. */
    BAN,
    /** Keeps the target from chatting on the scope's servers. */
    MUTE,
    /** A recorded warning; enough of them escalate to something stronger. */
    WARN,
    /** A one-off disconnect, kept only as history. */
    KICK;

    /** Whether a punishment of this type stays in force until it expires or is revoked. */
    public boolean isLasting() {
        return this != KICK;
    }
}
