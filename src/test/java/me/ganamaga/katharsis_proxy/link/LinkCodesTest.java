package me.ganamaga.katharsis_proxy.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkCodesTest {

    @Test
    @DisplayName("codes are eight characters without look-alikes, and differ")
    void generates() {
        LinkCodes codes = new LinkCodes();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String code = codes.generate();
            assertEquals(LinkCodes.LENGTH, code.length());
            assertTrue(code.chars().allMatch(c -> LinkCodes.ALPHABET.indexOf(c) >= 0), code);
            seen.add(code);
        }
        assertEquals(1_000, seen.size());
        assertTrue(LinkCodes.ALPHABET.chars().noneMatch(c -> "0O1IL".indexOf(c) >= 0));
    }

    @Test
    @DisplayName("typed codes are read loosely but strictly validated")
    void normalises() {
        assertEquals(Optional.of("ABCD2345"), LinkCodes.normalise("abcd-2345"));
        assertEquals(Optional.of("ABCD2345"), LinkCodes.normalise(" ABCD 2345 "));
        assertEquals(Optional.empty(), LinkCodes.normalise("ABCD234"), "too short");
        assertEquals(Optional.empty(), LinkCodes.normalise("ABCD23456"), "too long");
        assertEquals(Optional.empty(), LinkCodes.normalise("ABCD2340"), "0 is never generated");
        assertEquals(Optional.empty(), LinkCodes.normalise("ABCD'--;"));
    }
}
