package me.ganamaga.katharsis_proxy.auth;

import me.ganamaga.katharsis_proxy.database.AuthAccountRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.entity.AuthAccount;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration, login, and who is logged in right now.
 *
 * <p>Nothing here touches Velocity or LimboAPI, and every method that reads the
 * database or hashes a password blocks: callers run them off the network and
 * event threads.</p>
 */
public final class AuthService {

    /** How a joining player is to be let in, decided before they log in. */
    public sealed interface JoinDecision {

        /** Send them through Mojang's login; they need no password. */
        record Premium() implements JoinDecision {
        }

        /** Let them in offline, to log in or register with a password. */
        record Password() implements JoinDecision {
        }

        /**
         * The name is claimed in another case. Refused, since an offline-mode
         * uuid follows the exact spelling and the player would find none of
         * their things.
         */
        record WrongCase(String registeredName) implements JoinDecision {
        }

        /** The accounts cannot be read, so nobody can be checked. */
        record Unavailable() implements JoinDecision {
        }
    }

    public sealed interface RegisterResult {

        record Registered() implements RegisterResult {
        }

        record AlreadyRegistered() implements RegisterResult {
        }

        record Refused(PasswordPolicy.Problem problem) implements RegisterResult {
        }
    }

    public enum LoginResult {
        SUCCESS,
        WRONG_PASSWORD,
        NOT_REGISTERED
    }

    private final Logger logger;
    private final DatabaseManager database;
    private final AuthAccountRepository accounts;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final MojangProfiles mojang;
    private final boolean premiumSkip;
    private final Clock clock;

    /** Players logged in, and the connection they logged in on. */
    private final Map<UUID, Object> authenticated = new ConcurrentHashMap<>();

    public AuthService(Logger logger, DatabaseManager database, AuthAccountRepository accounts, PasswordHasher hasher,
                       PasswordPolicy policy, MojangProfiles mojang, boolean premiumSkip, Clock clock) {
        this.logger = logger;
        this.database = database;
        this.accounts = accounts;
        this.hasher = hasher;
        this.policy = policy;
        this.mojang = mojang;
        this.premiumSkip = premiumSkip;
        this.clock = clock;
    }

    public PasswordPolicy policy() {
        return policy;
    }

    /**
     * How {@code username} is to join. A name claimed by a Mojang account always
     * goes through Mojang, even with premium skip since turned off: it has no
     * password to log in with any other way.
     */
    public JoinDecision decide(String username) {
        if (!database.isAvailable()) {
            return new JoinDecision.Unavailable();
        }
        Optional<AuthAccount> account;
        try {
            account = accounts.find(username);
        } catch (SQLException | RuntimeException e) {
            logger.error("Could not look up the account of {}.", username, e);
            return new JoinDecision.Unavailable();
        }
        if (account.isPresent()) {
            AuthAccount existing = account.get();
            if (existing.premium()) {
                return new JoinDecision.Premium();
            }
            if (!existing.username().equals(username)) {
                return new JoinDecision.WrongCase(existing.username());
            }
            return new JoinDecision.Password();
        }
        if (premiumSkip && mojang.lookup(username) == MojangProfiles.Lookup.PREMIUM) {
            return new JoinDecision.Premium();
        }
        return new JoinDecision.Password();
    }

    /** Whether {@code username} has a password to log in with, rather than one to choose. */
    public boolean isRegistered(String username) throws SQLException {
        return accounts.find(username).filter(account -> !account.premium()).isPresent();
    }

    /** Claims {@code username} with a password, after checking it against the policy. */
    public RegisterResult register(String username, UUID playerUuid, String password, String confirmation)
            throws SQLException {
        Optional<PasswordPolicy.Problem> problem = policy.check(username, password, confirmation);
        if (problem.isPresent()) {
            return new RegisterResult.Refused(problem.get());
        }
        // Checked first so a taken name does not cost a hash.
        if (accounts.find(username).isPresent()) {
            return new RegisterResult.AlreadyRegistered();
        }
        AuthAccount account = AuthAccount.registered(username, playerUuid, hasher.hash(password), clock.instant());
        if (!accounts.insert(account)) {
            return new RegisterResult.AlreadyRegistered();
        }
        return new RegisterResult.Registered();
    }

    /**
     * Checks {@code password} against the stored hash. On success the hash is
     * redone if it was made under older Argon2 settings.
     */
    public LoginResult login(String username, String password) throws SQLException {
        Optional<AuthAccount> account = accounts.find(username);
        if (account.isEmpty()) {
            return LoginResult.NOT_REGISTERED;
        }
        Optional<String> hash = account.get().password();
        if (hash.isEmpty() || !hasher.verify(password, hash.get())) {
            return LoginResult.WRONG_PASSWORD;
        }
        accounts.recordLogin(username, clock.instant());
        if (hasher.needsRehash(hash.get())) {
            accounts.updatePassword(username, hasher.hash(password));
            logger.info("Rehashed the password of {} with the current Argon2 settings.", username);
        }
        return LoginResult.SUCCESS;
    }

    /** Records that Mojang logged {@code username} in, claiming the name for its account. */
    public void recordPremium(String username, UUID playerUuid) throws SQLException {
        if (!accounts.savePremium(username, playerUuid, clock.instant())) {
            logger.warn("Mojang logged {} ({}) in, but the name is claimed with a password; left as it is.",
                    username, playerUuid);
        }
    }

    /**
     * Whether this connection of {@code playerId} has logged in. The connection
     * is the proxy's player object: a new connection under the same uuid starts
     * out logged out, and the old one leaving cannot log the new one out.
     */
    public boolean isAuthenticated(UUID playerId, Object connection) {
        return authenticated.get(playerId) == connection;
    }

    public void markAuthenticated(UUID playerId, Object connection) {
        authenticated.put(playerId, connection);
    }

    /** Called on disconnect: the next connection logs in again. */
    public void forget(UUID playerId, Object connection) {
        authenticated.remove(playerId, connection);
    }
}
