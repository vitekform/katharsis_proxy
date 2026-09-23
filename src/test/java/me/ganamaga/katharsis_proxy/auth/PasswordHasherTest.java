package me.ganamaga.katharsis_proxy.auth;

import me.ganamaga.katharsis_proxy.config.AuthConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(new AuthConfig.Argon2Config());

    @Test
    @DisplayName("hashes are Argon2id in PHC format with the configured cost")
    void phcFormat() {
        String hash = hasher.hash("correct horse");

        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), hash);
        assertFalse(hash.contains("correct horse"));
    }

    @Test
    @DisplayName("the right password verifies and a wrong one does not")
    void verifies() {
        String hash = hasher.hash("correct horse");

        assertTrue(hasher.verify("correct horse", hash));
        assertFalse(hasher.verify("correct horsE", hash));
        assertFalse(hasher.verify("", hash));
    }

    @Test
    @DisplayName("every hash gets its own salt")
    void saltedPerHash() {
        assertNotEquals(hasher.hash("correct horse"), hasher.hash("correct horse"));
    }

    @Test
    @DisplayName("an unreadable or foreign hash never verifies")
    void garbageNeverMatches() {
        assertFalse(hasher.verify("anything", null));
        assertFalse(hasher.verify("anything", ""));
        assertFalse(hasher.verify("anything", "$argon2id$garbage"));
        assertFalse(hasher.verify("anything", "$2a$10$abcdefghijklmnopqrstuu5Z3tq4a5Ptb4ZxlJ4LLu3rhyk5eDlvW"));
    }

    @Test
    @DisplayName("a hash made with other settings still verifies and asks to be redone")
    void rehashOnNewSettings() {
        PasswordHasher cheap = new PasswordHasher(new AuthConfig.Argon2Config(1024, 1, 1));
        String old = cheap.hash("correct horse");

        assertTrue(hasher.verify("correct horse", old), "parameters come from the hash itself");
        assertTrue(hasher.needsRehash(old));
        assertFalse(hasher.needsRehash(hasher.hash("correct horse")));
        assertTrue(hasher.needsRehash("not a hash"));
    }
}
