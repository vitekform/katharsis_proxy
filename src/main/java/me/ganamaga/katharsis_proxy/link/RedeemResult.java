package me.ganamaga.katharsis_proxy.link;

import me.ganamaga.katharsis_proxy.entity.AccountLink;

/** What happened when a player typed a link code. */
public sealed interface RedeemResult {

    /** The accounts are now linked, and the code is used up. */
    record Linked(AccountLink link) implements RedeemResult {
    }

    /** No such code, or it has expired or been used. */
    record InvalidCode() implements RedeemResult {
    }

    /** The player's account is already linked. The code was left unused. */
    record PlayerAlreadyLinked(AccountLink link) implements RedeemResult {
    }

    /** The code's Discord account got linked to someone else in the meantime. */
    record DiscordAlreadyLinked() implements RedeemResult {
    }
}
