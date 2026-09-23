package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.Punishment;

/**
 * Told about every punishment once it is stored, to enforce it, announce it
 * or log it. Called on the thread that issued it, which is never an event
 * thread; an observer that throws is logged and does not stop the others.
 */
public interface PunishmentObserver {

    /** {@code punishment} was issued. */
    default void issued(Punishment punishment) {
    }

    /** {@code punishment} was lifted; its revocation is filled in. */
    default void revoked(Punishment punishment) {
    }
}
