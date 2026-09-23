package me.ganamaga.katharsis_proxy.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(8, 12);

    @Test
    @DisplayName("a password within the limits is accepted")
    void accepts() {
        assertEquals(Optional.empty(), policy.check("Steve", "hunter22", "hunter22"));
        assertEquals(Optional.empty(), policy.check("Steve", "123456789012", "123456789012"));
    }

    @Test
    @DisplayName("each problem is reported")
    void refuses() {
        assertEquals(Optional.of(PasswordPolicy.Problem.MISMATCH), policy.check("Steve", "hunter22", "hunter23"));
        assertEquals(Optional.of(PasswordPolicy.Problem.TOO_SHORT), policy.check("Steve", "hunter2", "hunter2"));
        assertEquals(Optional.of(PasswordPolicy.Problem.TOO_LONG),
                policy.check("Steve", "1234567890123", "1234567890123"));
        assertEquals(Optional.of(PasswordPolicy.Problem.IS_USERNAME),
                policy.check("SteveGreat", "stevegreat", "stevegreat"));
    }

    @Test
    @DisplayName("length counts characters, not UTF-16 units")
    void countsCodePoints() {
        String emoji = "🔒".repeat(8);
        assertEquals(Optional.empty(), policy.check("Steve", emoji, emoji), "8 characters, 16 units");
    }
}
