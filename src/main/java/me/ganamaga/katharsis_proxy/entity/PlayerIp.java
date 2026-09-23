package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An address an account has joined from.
 *
 * @param uuid      the account
 * @param ip        the address, as {@link java.net.InetAddress#getHostAddress()} writes it
 * @param firstSeen when the account first joined from it
 * @param lastSeen  when the account last joined from it
 * @param logins    how many sessions came from it
 */
public record PlayerIp(UUID uuid, String ip, Instant firstSeen, Instant lastSeen, int logins) {

    public PlayerIp {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (ip.isEmpty() || ip.length() > Punishment.IP_MAX_LENGTH) {
            throw new IllegalArgumentException("ip must be 1-" + Punishment.IP_MAX_LENGTH + " characters");
        }
    }
}
