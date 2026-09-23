package me.ganamaga.katharsis_proxy.punish;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a command names: an account, with the address it last joined from if
 * known, or a bare address.
 *
 * @param uuid the account; {@code null} when an address was named
 * @param name the account's name, or the address, for display
 * @param ip   the account's last address, or the address named; {@code null} if unknown
 */
public record Target(UUID uuid, String name, String ip) {

    public Target {
        Objects.requireNonNull(name, "name");
        if (uuid == null && ip == null) {
            throw new IllegalArgumentException("a target needs an account or an address");
        }
    }

    public static Target address(String ip) {
        return new Target(null, ip, ip);
    }

    /** Whether an address rather than an account was named. */
    public boolean isAddress() {
        return uuid == null;
    }
}
