package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An account that has played on the network, as last seen.
 *
 * <p>Lets staff punish players who are offline, by name. A name is not unique
 * here: an offline-mode account and a premium one can both have held it, and a
 * premium player can rename; the most recently seen account wins.</p>
 *
 * @param uuid      the account
 * @param name      the name it last joined with
 * @param firstSeen when it first joined
 * @param lastSeen  when it last joined
 */
public record PlayerHistory(UUID uuid, String name, Instant firstSeen, Instant lastSeen) {

    public PlayerHistory {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (name.isEmpty() || name.length() > AuthAccount.USERNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("name must be 1-" + AuthAccount.USERNAME_MAX_LENGTH + " characters");
        }
    }
}
