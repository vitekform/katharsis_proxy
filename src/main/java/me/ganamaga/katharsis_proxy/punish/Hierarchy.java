package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;

import java.util.UUID;

/**
 * Decides whether an issuer may punish an account at all.
 */
@FunctionalInterface
public interface Hierarchy {

    /** Lets anyone punish anyone. */
    Hierarchy NONE = (issuer, target) -> Verdict.ALLOWED;

    enum Verdict {
        ALLOWED,
        /** Nobody may punish the target. */
        EXEMPT,
        /** The target ranks at or above the issuer. */
        OUTRANKED
    }

    /** May be slow: it may look the target up. Never called on an event thread. */
    Verdict check(IssuerRef issuer, UUID target);
}
