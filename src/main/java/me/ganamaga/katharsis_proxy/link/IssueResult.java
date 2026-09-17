package me.ganamaga.katharsis_proxy.link;

import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.entity.LinkCode;

/** What happened when a Discord user asked for a link code. */
public sealed interface IssueResult {

    /** A new code, which replaces any earlier one for the same user. */
    record Issued(LinkCode code) implements IssueResult {
    }

    /** The Discord account is already linked, so no code was made. */
    record AlreadyLinked(AccountLink link) implements IssueResult {
    }
}
