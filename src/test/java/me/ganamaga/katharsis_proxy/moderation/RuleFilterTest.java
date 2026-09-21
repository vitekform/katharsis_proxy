package me.ganamaga.katharsis_proxy.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline layer: one entry has to catch every spelling of its word. */
class RuleFilterTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleFilterTest.class);

    private static boolean flags(RuleFilter filter, String message) {
        return filter.check(TextNormalizer.normalize(message)).isFlagged();
    }

    private static RuleFilter words(String... terms) {
        return RuleFilter.compile(LOGGER, List.of(terms), List.of(), List.of());
    }

    @Test
    @DisplayName("catches a term however it is spaced, decorated or spelled")
    void catchesObfuscation() {
        RuleFilter filter = words("racist");
        assertTrue(flags(filter, "you are racist"));
        assertTrue(flags(filter, "you are RACIST"));
        assertTrue(flags(filter, "you are r a c i s t"));
        assertTrue(flags(filter, "you are r.a.c.i.s.t"));
        assertTrue(flags(filter, "you are r4c1st"));
        assertTrue(flags(filter, "you are r@c!st"));
        assertTrue(flags(filter, "you are ráčìsť".replace('č', 'c')));
        assertTrue(flags(filter, "you are ｒａｃｉｓｔ"));
        assertTrue(flags(filter, "you are ra​ci‍st"));
        assertFalse(flags(filter, "you are lovely"));
    }

    @Test
    @DisplayName("catches a term stretched out or with its letters doubled")
    void catchesStretching() {
        RuleFilter filter = words("racist");
        assertTrue(flags(filter, "raaaciiisttt"));
        assertTrue(flags(filter, "rrraacist"));

        // A term with a double of its own is matched with one letter too.
        RuleFilter doubled = words("buffoon");
        assertTrue(flags(doubled, "you buffoon"));
        assertTrue(flags(doubled, "you bufoon"));
        assertTrue(flags(doubled, "you buuuffooooon"));
    }

    @Test
    @DisplayName("names the term that matched")
    void namesTheTerm() {
        ModerationVerdict verdict = words("racist").check(TextNormalizer.normalize("r a c i s t"));
        assertEquals(ModerationSource.RULES, verdict.source());
        assertEquals("word: racist", verdict.reason());
    }

    @Test
    @DisplayName("does not flag an innocent word that happens to contain a term")
    void honoursTheAllowList() {
        RuleFilter filter = RuleFilter.compile(LOGGER, List.of("cunt", "ass"), List.of(),
                List.of("scunthorpe", "assassin"));
        assertFalse(flags(filter, "I live in Scunthorpe"));
        assertFalse(flags(filter, "the assassin won"));
        // The term itself is still caught, in the same message as an allowed word.
        assertTrue(flags(filter, "the assassin from Scunthorpe is an ass"));
    }

    @Test
    @DisplayName("matches expressions against the message as typed as well as the folded form")
    void matchesPatterns() {
        RuleFilter filter = RuleFilter.compile(LOGGER, List.of(),
                List.of("(?:https?://)?discord\\.gg/\\w+"), List.of());
        assertTrue(flags(filter, "join discord.gg/abc123"));
        assertTrue(flags(filter, "join https://discord.gg/abc123"));
        assertFalse(flags(filter, "join my server"));

        ModerationVerdict verdict = filter.check(TextNormalizer.normalize("discord.gg/abc"));
        assertEquals(ModerationSource.RULES, verdict.source());
        assertTrue(verdict.reason().startsWith("pattern: "));
    }

    @Test
    @DisplayName("an expression needing digits still works, since they survive in the folded form")
    void keepsDigitsForPatterns() {
        RuleFilter filter = RuleFilter.compile(LOGGER, List.of(),
                List.of("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"), List.of());
        assertTrue(flags(filter, "connect to 192.168.0.1 now"));
        assertFalse(flags(filter, "connect to my server"));
    }

    @Test
    @DisplayName("skips an expression that does not compile and keeps the rest")
    void skipsBrokenExpressions() {
        RuleFilter filter = RuleFilter.compile(LOGGER, List.of("racist"), List.of("([unclosed"), List.of());
        assertEquals(1, filter.size());
        assertTrue(flags(filter, "racist"));
    }

    @Test
    @DisplayName("ignores entries with nothing to match and reports an empty filter")
    void ignoresEmptyEntries() {
        assertTrue(RuleFilter.compile(LOGGER, List.of("", "   ", "!!!"), List.of(""), List.of()).isEmpty());
        assertTrue(RuleFilter.empty().isEmpty());
        assertFalse(flags(RuleFilter.empty(), "anything at all"));
    }

    @Test
    @DisplayName("reads a term written in leetspeak as the word it spells")
    void foldsTheTermItself() {
        assertTrue(flags(words("r4c1st"), "you are racist"));
    }
}
