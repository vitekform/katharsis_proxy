package me.ganamaga.katharsis_proxy.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every way of spelling one word has to fold down to the same letters. */
class TextNormalizerTest {

    private static String squeezed(String text) {
        return TextNormalizer.normalize(text).squeezed();
    }

    @Test
    @DisplayName("keeps the original untouched")
    void keepsOriginal() {
        String written = "Hello, World!";
        assertEquals(written, TextNormalizer.normalize(written).original());
    }

    @Test
    @DisplayName("lower cases and drops punctuation, keeping one space between words")
    void normalizesSpacing() {
        assertEquals("hello world", TextNormalizer.normalize("  Hello,   World  ").normalized());
        assertEquals("helloworld", squeezed("  Hello,   World  "));
    }

    @Test
    @DisplayName("leaves the normalized form readable, and lets only the squeezed one read leetspeak")
    void keepsTheHonestFormReadable() {
        NormalizedText text = TextNormalizer.normalize("Hello, World!! I have 3 apples");
        assertEquals("hello world i have 3 apples", text.normalized());
        // The suspicious reading is allowed to be nonsense; nobody ever sees it.
        assertEquals("helloworldiiihaveeapples", text.squeezed());
    }

    @Test
    @DisplayName("closes up a word spelled out with spaces or punctuation")
    void closesUpSpelling() {
        assertEquals("racist", squeezed("r a c i s t"));
        assertEquals("racist", squeezed("r.a.c.i.s.t"));
        assertEquals("racist", squeezed("r-a-c-i-s-t"));
        assertEquals("racist", squeezed("r_a_c_i_s_t"));
    }

    @Test
    @DisplayName("maps leetspeak onto the letters it stands for")
    void mapsLeetspeak() {
        assertEquals("racist", squeezed("r4c1st"));
        assertEquals("racist", squeezed("r@c!st"));
        assertEquals("assist", squeezed("4$$1$7"));
    }

    @Test
    @DisplayName("strips accents and reads full-width and circled letters")
    void stripsDecoration() {
        assertEquals("racist", squeezed("ráčíšt".replace('č', 'c').replace('š', 's')));
        assertEquals("racist", squeezed("rácîsť"));
        assertEquals("racist", squeezed("ｒａｃｉｓｔ"));
        assertEquals("racist", squeezed("ⓡⓐⓒⓘⓢⓣ"));
    }

    @Test
    @DisplayName("removes zero-width characters that break a word up invisibly")
    void removesInvisibles() {
        assertEquals("racist", squeezed("ra​ci‍st"));
        assertEquals("racist", squeezed("r­a­c­i­s­t"));
        assertEquals("racist", squeezed("‮racist‬"));
    }

    @Test
    @DisplayName("reads Cyrillic and Greek look-alikes as the Latin letters they imitate")
    void mapsLookAlikes() {
        // Cyrillic а, с, і and Greek ο, τ among Latin letters.
        assertEquals("racist", squeezed("rаcіst"));
        assertEquals("pot", squeezed("ροτ"));
    }

    @Test
    @DisplayName("treats a line break as a gap rather than gluing words together")
    void breaksOnControlCharacters() {
        assertEquals("one two", TextNormalizer.normalize("one\ntwo").normalized());
    }

    @Test
    @DisplayName("reports text with no letters or digits left as blank")
    void reportsBlank() {
        assertTrue(TextNormalizer.normalize("!!! ... ???").isBlank());
        assertTrue(TextNormalizer.normalize("").isBlank());
        assertFalse(TextNormalizer.normalize("hi").isBlank());
    }
}
