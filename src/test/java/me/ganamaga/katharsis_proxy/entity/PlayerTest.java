package me.ganamaga.katharsis_proxy.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    @DisplayName("accepts a language that fits the column")
    void acceptsFittingLanguage() {
        assertEquals("en_US", new Player(ID, "en_US").language());
        assertEquals("cs", new Player(ID, "cs").language());
    }

    @Test
    @DisplayName("rejects a language wider than the column")
    void rejectsOverlongLanguage() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new Player(ID, "en_US_X"));
        assertEquals("language 'en_US_X' exceeds 5 characters", thrown.getMessage());
    }

    @Test
    @DisplayName("rejects null fields")
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> new Player(null, "en_US"));
        assertThrows(NullPointerException.class, () -> new Player(ID, null));
    }

    @Test
    @DisplayName("withLanguage keeps the identity and validates the new value")
    void withLanguage() {
        Player player = new Player(ID, "en_US").withLanguage("cs_CZ");
        assertEquals(ID, player.uuid());
        assertEquals("cs_CZ", player.language());
        assertThrows(IllegalArgumentException.class, () -> player.withLanguage("toolong"));
    }
}
