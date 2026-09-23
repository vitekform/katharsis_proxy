package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.database.AuthAccountRepository;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.entity.AuthAccount;
import me.ganamaga.katharsis_proxy.entity.PlayerHistory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Works out whom a staff member means by what they typed: an address, a
 * player online, or a player who has played here before.
 */
public final class TargetResolver {

    /** Finds a player online by name, with the address they are connected from. */
    @FunctionalInterface
    public interface OnlineLookup {
        Optional<Target> find(String name);
    }

    private final OnlineLookup online;
    private final PlayerHistoryRepository history;
    private final AuthAccountRepository accounts;
    private final boolean offlineMode;

    /**
     * @param accounts    also consulted for names; {@code null} if accounts are not kept
     * @param offlineMode whether the proxy derives uuids from names, so a name
     *                    nobody has joined under still has a known uuid
     */
    public TargetResolver(OnlineLookup online, PlayerHistoryRepository history, AuthAccountRepository accounts,
                          boolean offlineMode) {
        this.online = online;
        this.history = history;
        this.accounts = accounts;
        this.offlineMode = offlineMode;
    }

    /**
     * Resolves {@code input}. Blocks on the database unless the player is online.
     *
     * @param force on an offline-mode proxy, accept a name nobody has joined
     *              under, by the uuid it would log in with
     * @return the target, or empty if nobody by that name is known
     */
    public Optional<Target> resolve(String input, boolean force) throws SQLException {
        Optional<String> address = IpAddresses.parseLiteral(input);
        if (address.isPresent()) {
            return Optional.of(Target.address(address.get()));
        }
        Optional<Target> onlineTarget = online.find(input);
        if (onlineTarget.isPresent()) {
            return onlineTarget;
        }
        Optional<PlayerHistory> seen = history.findLatestByName(input);
        if (seen.isPresent()) {
            UUID uuid = seen.get().uuid();
            return Optional.of(new Target(uuid, seen.get().name(), history.lastIp(uuid).orElse(null)));
        }
        if (accounts != null) {
            Optional<AuthAccount> account = accounts.find(input);
            if (account.isPresent()) {
                UUID uuid = account.get().playerUuid();
                return Optional.of(new Target(uuid, account.get().username(), history.lastIp(uuid).orElse(null)));
            }
        }
        if (force && offlineMode && isName(input)) {
            return Optional.of(new Target(offlineUuid(input), input, null));
        }
        return Optional.empty();
    }

    /** The uuid an offline-mode proxy gives {@code name}, as Velocity and vanilla derive it. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isName(String input) {
        return input.matches("[A-Za-z0-9_]{1," + AuthAccount.USERNAME_MAX_LENGTH + "}");
    }
}
