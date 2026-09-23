package me.ganamaga.katharsis_proxy.auth;

import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One player's stay in the login void, from arriving to logging in or being
 * kicked.
 *
 * <p>Everything they type arrives through {@link #input}, and only
 * {@code /login} and {@code /register} do anything. Checking a password is slow
 * on purpose, so the work runs on the given executor, one attempt at a time:
 * an attempt typed while the last is still being checked is turned away rather
 * than queued.</p>
 */
public final class AuthSession {

    /** How the session reaches the player; LimboAPI in production, a recorder in tests. */
    public interface Client {

        /** Shows the login title, and says how to log in or register. */
        void prompt(boolean registered);

        void message(MessageKey key, TagResolver... placeholders);

        void kick(MessageKey key, TagResolver... placeholders);

        /** Lets the player out of the void, on to the network, after telling them {@code key}. */
        void pass(MessageKey key);
    }

    private final Logger logger;
    private final AuthService auth;
    private final Executor executor;
    private final Client client;
    private final String username;
    private final UUID playerId;
    private final Object connection;
    private final int maxAttempts;

    /** Set once the player has logged in, been kicked, or left. Nothing happens after. */
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicInteger failures = new AtomicInteger();

    /** Only picks the prompt; /login and /register check the database themselves. */
    private volatile boolean registered;

    /**
     * @param connection the proxy's object for this connection, which is what
     *                   gets marked as logged in
     */
    public AuthSession(Logger logger, AuthService auth, Executor executor, Client client, String username,
                       UUID playerId, Object connection, int maxAttempts) {
        this.logger = logger;
        this.auth = auth;
        this.executor = executor;
        this.client = client;
        this.username = username;
        this.playerId = playerId;
        this.connection = connection;
        this.maxAttempts = maxAttempts;
    }

    /** The player has arrived in the void. */
    public void start() {
        work(() -> {
            registered = auth.isRegistered(username);
            client.prompt(registered);
        });
    }

    /** A chat message or command, exactly as typed. */
    public void input(String typed) {
        if (finished.get()) {
            return;
        }
        String text = typed.strip();
        if (!text.startsWith("/")) {
            client.message(registered ? MessageKey.AUTH_PROMPT_LOGIN : MessageKey.AUTH_PROMPT_REGISTER);
            return;
        }
        String[] words = text.substring(1).split("\\s+");
        String[] arguments = new String[words.length - 1];
        System.arraycopy(words, 1, arguments, 0, arguments.length);
        switch (words[0].toLowerCase(Locale.ROOT)) {
            case "login", "l" -> login(arguments);
            case "register", "reg" -> register(arguments);
            default -> client.message(MessageKey.AUTH_ONLY_AUTH_COMMANDS);
        }
    }

    /** The login window has run out. */
    public void timeout() {
        if (finished.compareAndSet(false, true)) {
            logger.info("{} did not log in in time.", username);
            client.kick(MessageKey.AUTH_KICK_TIMEOUT);
        }
    }

    /** The player left the void on their own. */
    public void close() {
        finished.set(true);
    }

    public boolean isFinished() {
        return finished.get();
    }

    private void login(String[] arguments) {
        if (arguments.length != 1) {
            client.message(MessageKey.AUTH_LOGIN_USAGE);
            return;
        }
        attempt(() -> {
            switch (auth.login(username, arguments[0])) {
                case SUCCESS -> {
                    logger.info("{} logged in.", username);
                    succeed(MessageKey.AUTH_LOGGED_IN);
                }
                case NOT_REGISTERED -> client.message(MessageKey.AUTH_NOT_REGISTERED);
                case WRONG_PASSWORD -> wrongPassword();
            }
        });
    }

    private void register(String[] arguments) {
        if (arguments.length != 2) {
            client.message(MessageKey.AUTH_REGISTER_USAGE);
            return;
        }
        attempt(() -> {
            switch (auth.register(username, playerId, arguments[0], arguments[1])) {
                case AuthService.RegisterResult.Registered ignored -> {
                    logger.info("{} ({}) registered.", username, playerId);
                    succeed(MessageKey.AUTH_REGISTERED);
                }
                case AuthService.RegisterResult.AlreadyRegistered ignored -> {
                    registered = true;
                    client.message(MessageKey.AUTH_ALREADY_REGISTERED);
                }
                case AuthService.RegisterResult.Refused refused -> refuse(refused.problem());
            }
        });
    }

    private void wrongPassword() {
        int left = maxAttempts - failures.incrementAndGet();
        if (left > 0) {
            client.message(MessageKey.AUTH_WRONG_PASSWORD, Placeholder.unparsed("attempts", Integer.toString(left)));
            return;
        }
        if (finished.compareAndSet(false, true)) {
            logger.info("{} was kicked after {} wrong password(s).", username, maxAttempts);
            client.kick(MessageKey.AUTH_KICK_ATTEMPTS);
        }
    }

    private void refuse(PasswordPolicy.Problem problem) {
        PasswordPolicy policy = auth.policy();
        switch (problem) {
            case TOO_SHORT -> client.message(MessageKey.AUTH_PASSWORD_TOO_SHORT,
                    Placeholder.unparsed("min", Integer.toString(policy.minLength())));
            case TOO_LONG -> client.message(MessageKey.AUTH_PASSWORD_TOO_LONG,
                    Placeholder.unparsed("max", Integer.toString(policy.maxLength())));
            case MISMATCH -> client.message(MessageKey.AUTH_PASSWORD_MISMATCH);
            case IS_USERNAME -> client.message(MessageKey.AUTH_PASSWORD_IS_USERNAME);
        }
    }

    private void succeed(MessageKey key) {
        if (finished.compareAndSet(false, true)) {
            // Before passing them on, so the server switch that follows is allowed.
            auth.markAuthenticated(playerId, connection);
            client.pass(key);
        }
    }

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException;
    }

    /** Runs one /login or /register, unless the previous one is still running. */
    private void attempt(Work work) {
        if (!busy.compareAndSet(false, true)) {
            client.message(MessageKey.AUTH_BUSY);
            return;
        }
        work(() -> {
            try {
                work.run();
            } finally {
                busy.set(false);
            }
        });
    }

    private void work(Work work) {
        try {
            executor.execute(() -> {
                if (finished.get()) {
                    return;
                }
                try {
                    work.run();
                } catch (SQLException | RuntimeException e) {
                    logger.error("Could not check the login of {} ({}).", username, playerId, e);
                    client.message(MessageKey.AUTH_UNAVAILABLE);
                }
            });
        } catch (RejectedExecutionException e) {
            // Too many logins at once, or the proxy is shutting down.
            busy.set(false);
            client.message(MessageKey.AUTH_BUSY);
        }
    }
}
