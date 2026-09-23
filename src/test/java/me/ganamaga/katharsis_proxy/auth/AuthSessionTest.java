package me.ganamaga.katharsis_proxy.auth;

import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSessionTest {

    private static final UUID STEVE = UUID.fromString("5627dd98-e6be-3c21-b8a8-e92344183641");

    /** Stands in for the proxy's player object. */
    private final Object connection = new Object();

    /** Everything the session did to the player, in order. */
    private final List<String> events = new ArrayList<>();

    private final AuthSession.Client client = new AuthSession.Client() {
        @Override
        public void prompt(boolean registered) {
            events.add(registered ? "prompt:login" : "prompt:register");
        }

        @Override
        public void message(MessageKey key, TagResolver... placeholders) {
            events.add("message:" + key.key());
        }

        @Override
        public void kick(MessageKey key, TagResolver... placeholders) {
            events.add("kick:" + key.key());
        }

        @Override
        public void pass(MessageKey key) {
            events.add("pass:" + key.key());
        }
    };

    private AuthTestSupport support;

    /** Work queued for the auth threads, run by the test when it chooses. */
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        support = new AuthTestSupport(directory, true);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private AuthSession session(Executor executor) {
        return new AuthSession(LoggerFactory.getLogger(AuthSessionTest.class), support.service, executor, client,
                "Steve", STEVE, connection, 3);
    }

    private AuthSession session() {
        return session(Runnable::run);
    }

    @Test
    @DisplayName("a new player is asked to register, registers, and is let through")
    void registerFlow() {
        AuthSession session = session();
        session.start();
        session.input("/register hunter2hunter2 hunter2hunter2");

        assertEquals(List.of("prompt:register", "pass:auth-registered"), events);
        assertTrue(support.service.isAuthenticated(STEVE, connection));
        assertTrue(session.isFinished());
    }

    @Test
    @DisplayName("a returning player is asked to log in, and aliases work in any case")
    void loginFlow() throws Exception {
        support.service.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        AuthSession session = session();
        session.start();
        session.input("  /L   hunter2hunter2 ");

        assertEquals(List.of("prompt:login", "pass:auth-logged-in"), events);
        assertTrue(support.service.isAuthenticated(STEVE, connection));
    }

    @Test
    @DisplayName("chat and other commands do nothing but repeat how to get in")
    void everythingElseRefused() {
        AuthSession session = session();
        session.start();
        session.input("hello?");
        session.input("/spawn");
        session.input("/lobby");

        assertEquals(List.of("prompt:register", "message:auth-prompt-register",
                "message:auth-only-auth-commands", "message:auth-only-auth-commands"), events);
        assertFalse(support.service.isAuthenticated(STEVE, connection));
    }

    @Test
    @DisplayName("wrong argument counts show the usage and cost no attempt")
    void usage() throws Exception {
        support.service.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        AuthSession session = session();
        for (int i = 0; i < 5; i++) {
            session.input("/login");
        }
        session.input("/register onlyone");

        assertEquals(5, events.stream().filter("message:auth-login-usage"::equals).count());
        assertEquals("message:auth-register-usage", events.getLast());
        assertFalse(session.isFinished());
    }

    @Test
    @DisplayName("password problems are explained and the player may try again")
    void registerRefusals() {
        AuthSession session = session();
        session.input("/register short short");
        session.input("/register hunter2hunter2 hunter2hunter3");
        session.input("/register steve1234 steve1234");
        session.input("/register Steve Steve");

        assertEquals(List.of("message:auth-password-too-short", "message:auth-password-mismatch",
                "pass:auth-registered"), events.subList(0, 3));
        assertTrue(session.isFinished());
    }

    @Test
    @DisplayName("logging in without an account, or registering a taken name, points the other way")
    void wrongCommand() throws Exception {
        session().input("/login hunter2hunter2");
        assertEquals(List.of("message:auth-not-registered"), events);

        events.clear();
        support.service.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        AuthSession session = session();
        session.input("/register different-one different-one");
        session.input("chat");
        assertEquals(List.of("message:auth-already-registered", "message:auth-prompt-login"), events);
    }

    @Test
    @DisplayName("the last allowed wrong password kicks, and nothing works after")
    void kickAfterAttempts() throws Exception {
        support.service.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        AuthSession session = session();
        session.input("/login wrong-one");
        session.input("/login wrong-two");
        session.input("/login wrong-three");
        session.input("/login hunter2hunter2");

        assertEquals(List.of("message:auth-wrong-password", "message:auth-wrong-password",
                "kick:auth-kick-attempts"), events);
        assertFalse(support.service.isAuthenticated(STEVE, connection));
    }

    @Test
    @DisplayName("running out of time kicks, once, and a late success is ignored")
    void timeout() throws Exception {
        support.service.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        AuthSession session = session(queued::add);
        session.input("/login hunter2hunter2");
        session.timeout();
        session.timeout();
        queued.forEach(Runnable::run);

        assertEquals(List.of("kick:auth-kick-timeout"), events);
        assertFalse(support.service.isAuthenticated(STEVE, connection));
    }

    @Test
    @DisplayName("a timeout after logging in does nothing")
    void timeoutAfterLogin() {
        AuthSession session = session();
        session.input("/register hunter2hunter2 hunter2hunter2");
        session.timeout();

        assertEquals(List.of("pass:auth-registered"), events);
    }

    @Test
    @DisplayName("only one attempt is checked at a time")
    void oneAtATime() throws Exception {
        support.service.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        AuthSession session = session(queued::add);
        session.input("/login wrong-one");
        session.input("/login wrong-two");
        assertEquals(List.of("message:auth-busy"), events);
        assertEquals(1, queued.size());

        queued.removeFirst().run();
        session.input("/login hunter2hunter2");
        queued.removeFirst().run();
        assertEquals(List.of("message:auth-busy", "message:auth-wrong-password", "pass:auth-logged-in"), events);
    }

    @Test
    @DisplayName("a full queue asks the player to wait instead of losing the attempt silently")
    void rejected() {
        AuthSession session = session(runnable -> {
            throw new RejectedExecutionException("full");
        });
        session.input("/register hunter2hunter2 hunter2hunter2");
        assertEquals(List.of("message:auth-busy"), events);

        events.clear();
        session.input("/register hunter2hunter2 hunter2hunter2");
        assertEquals(List.of("message:auth-busy"), events, "the rejected attempt did not stay marked as running");
    }

    @Test
    @DisplayName("a database failure is reported, not mistaken for a wrong password")
    void databaseDown() {
        AuthSession session = session();
        support.database.close();
        session.input("/login hunter2hunter2");
        session.input("/login hunter2hunter2");

        assertEquals(List.of("message:auth-unavailable", "message:auth-unavailable"), events);
        assertFalse(session.isFinished());
    }

    @Test
    @DisplayName("once the player has left, queued work does nothing")
    void closed() {
        AuthSession session = session(queued::add);
        session.input("/register hunter2hunter2 hunter2hunter2");
        session.close();
        queued.forEach(Runnable::run);

        assertTrue(events.isEmpty());
        assertFalse(support.service.isAuthenticated(STEVE, connection));
    }
}
