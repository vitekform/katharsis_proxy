package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.Punishment;

/** What came of asking {@link PunishmentService} to issue a punishment. */
public sealed interface IssueResult {

    /**
     * Stored and enforced.
     *
     * @param punishment     as stored
     * @param addressSkipped the address was meant to be held too, but none
     *                       usable is known
     * @param replaced       how many active punishments it replaced
     */
    record Issued(Punishment punishment, boolean addressSkipped, int replaced) implements IssueResult {
    }

    /** The same type is already in force on the same scope; nothing was stored. */
    record AlreadyActive(Punishment existing) implements IssueResult {
    }

    /** The target may not be punished at all. */
    record Exempt() implements IssueResult {
    }

    /** The target ranks at or above the issuer. */
    record Outranked() implements IssueResult {
    }

    /** Only the address was to be held, and none usable is known. */
    record NoAddress() implements IssueResult {
    }

    /** The database could not be reached. */
    record Unavailable() implements IssueResult {
    }
}
