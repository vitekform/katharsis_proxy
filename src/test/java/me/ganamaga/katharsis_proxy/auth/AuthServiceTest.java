package me.ganamaga.katharsis_proxy.auth;

import me.ganamaga.katharsis_proxy.config.AuthConfig;
import me.ganamaga.katharsis_proxy.entity.AuthAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private static final UUID STEVE = UUID.fromString("5627dd98-e6be-3c21-b8a8-e92344183641");

    private AuthTestSupport support;
    private AuthService auth;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        support = new AuthTestSupport(directory, true);
        auth = support.service;
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("a new name Mojang does not know logs in with a password")
    void newCrackedName() {
        assertInstanceOf(AuthService.JoinDecision.Password.class, auth.decide("Steve"));
        assertEquals(List.of("Steve"), support.mojang.asked);
    }

    @Test
    @DisplayName("a new name Mojang knows goes through Mojang; one it could not answer about uses a password")
    void newPremiumName() {
        support.mojang.answers.put("Notch", MojangProfiles.Lookup.PREMIUM);
        support.mojang.answers.put("Jeb_", MojangProfiles.Lookup.UNKNOWN);

        assertInstanceOf(AuthService.JoinDecision.Premium.class, auth.decide("Notch"));
        assertInstanceOf(AuthService.JoinDecision.Password.class, auth.decide("Jeb_"),
                "without an answer the player is asked for a password rather than locked out");
    }

    @Test
    @DisplayName("with premium skip off, Mojang is not asked")
    void premiumSkipOff(@TempDir Path directory) throws Exception {
        AuthTestSupport off = new AuthTestSupport(directory, false);
        try {
            off.mojang.answers.put("Notch", MojangProfiles.Lookup.PREMIUM);
            assertInstanceOf(AuthService.JoinDecision.Password.class, off.service.decide("Notch"));
            assertTrue(off.mojang.asked.isEmpty());
        } finally {
            off.close();
        }
    }

    @Test
    @DisplayName("a registered name stays a password account even if Mojang knows it")
    void registeredNameIsNotLookedUp() throws SQLException {
        auth.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        support.mojang.answers.put("Steve", MojangProfiles.Lookup.PREMIUM);

        assertInstanceOf(AuthService.JoinDecision.Password.class, auth.decide("Steve"));
        assertTrue(support.mojang.asked.isEmpty());
    }

    @Test
    @DisplayName("a registered name in another case is refused, naming the right spelling")
    void wrongCase() throws SQLException {
        auth.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");

        AuthService.JoinDecision decision = auth.decide("steve");
        assertEquals(new AuthService.JoinDecision.WrongCase("Steve"), decision);
    }

    @Test
    @DisplayName("a name Mojang claimed always goes through Mojang, even with premium skip off")
    void premiumAccountAlwaysMojang(@TempDir Path directory) throws Exception {
        AuthTestSupport off = new AuthTestSupport(directory, false);
        try {
            off.service.recordPremium("Notch", UUID.randomUUID());
            assertInstanceOf(AuthService.JoinDecision.Premium.class, off.service.decide("notch"));
        } finally {
            off.close();
        }
    }

    @Test
    @DisplayName("with the database down, nobody can be checked")
    void databaseDown() {
        support.database.close();
        assertInstanceOf(AuthService.JoinDecision.Unavailable.class, auth.decide("Steve"));
    }

    @Test
    @DisplayName("register, then log in with the same password and only that one")
    void registerThenLogin() throws SQLException {
        assertFalse(auth.isRegistered("Steve"));
        assertInstanceOf(AuthService.RegisterResult.Registered.class,
                auth.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2"));
        assertTrue(auth.isRegistered("Steve"));

        assertEquals(AuthService.LoginResult.SUCCESS, auth.login("Steve", "hunter2hunter2"));
        assertEquals(AuthService.LoginResult.WRONG_PASSWORD, auth.login("Steve", "hunter2hunter3"));
        assertEquals(AuthService.LoginResult.NOT_REGISTERED, auth.login("Alex", "hunter2hunter2"));

        String stored = support.accounts.find("Steve").orElseThrow().passwordHash();
        assertTrue(stored.startsWith("$argon2id$"), stored);
        assertFalse(stored.contains("hunter2"));
    }

    @Test
    @DisplayName("registering refuses a bad password before hashing, and a taken name")
    void registerRefusals() throws SQLException {
        assertEquals(new AuthService.RegisterResult.Refused(PasswordPolicy.Problem.TOO_SHORT),
                auth.register("Steve", STEVE, "short", "short"));
        assertEquals(new AuthService.RegisterResult.Refused(PasswordPolicy.Problem.MISMATCH),
                auth.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter3"));
        assertFalse(auth.isRegistered("Steve"));

        auth.register("Steve", STEVE, "hunter2hunter2", "hunter2hunter2");
        assertInstanceOf(AuthService.RegisterResult.AlreadyRegistered.class,
                auth.register("STEVE", UUID.randomUUID(), "other-password", "other-password"));
        assertEquals(AuthService.LoginResult.SUCCESS, auth.login("Steve", "hunter2hunter2"),
                "the first password still works");
    }

    @Test
    @DisplayName("a premium account has no password to log in with")
    void premiumCannotUsePassword() throws SQLException {
        auth.recordPremium("Notch", UUID.randomUUID());

        assertFalse(auth.isRegistered("Notch"));
        assertEquals(AuthService.LoginResult.WRONG_PASSWORD, auth.login("Notch", "anything-at-all"));
        assertInstanceOf(AuthService.RegisterResult.AlreadyRegistered.class,
                auth.register("Notch", UUID.randomUUID(), "anything-at-all", "anything-at-all"));
    }

    @Test
    @DisplayName("a login under old Argon2 settings upgrades the stored hash")
    void rehashOnLogin() throws SQLException {
        String old = new PasswordHasher(new AuthConfig.Argon2Config(2048, 1, 1)).hash("hunter2hunter2");
        support.accounts.insert(AuthAccount.registered("Steve", STEVE, old, AuthTestSupport.NOW));

        assertEquals(AuthService.LoginResult.SUCCESS, auth.login("Steve", "hunter2hunter2"));

        String now = support.accounts.find("Steve").orElseThrow().passwordHash();
        assertTrue(now.startsWith("$argon2id$v=19$m=1024,t=1,p=1$"), now);
        assertEquals(AuthService.LoginResult.SUCCESS, auth.login("Steve", "hunter2hunter2"));
    }

    @Test
    @DisplayName("being logged in belongs to one connection")
    void authenticatedPerConnection() {
        Object first = new Object();
        Object second = new Object();

        assertFalse(auth.isAuthenticated(STEVE, first));
        auth.markAuthenticated(STEVE, first);
        assertTrue(auth.isAuthenticated(STEVE, first));
        assertFalse(auth.isAuthenticated(STEVE, second), "a second connection under the same uuid is not");

        auth.markAuthenticated(STEVE, second);
        auth.forget(STEVE, first);
        assertTrue(auth.isAuthenticated(STEVE, second), "the old connection leaving does not log the new one out");

        auth.forget(STEVE, second);
        assertFalse(auth.isAuthenticated(STEVE, second));
    }
}
