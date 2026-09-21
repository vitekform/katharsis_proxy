package me.ganamaga.katharsis_proxy.moderation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Folds a chat message down to plain forms the filters can match reliably, so
 * that spelling a word out as {@code r a c i s t}, {@code r@c1st} or
 * {@code ⓡⓐⓒⓘⓢⓣ} does not get it past a rule written as {@code racist}.
 *
 * <p>Nothing here decides anything; it only rewrites text. Both forms in
 * {@link NormalizedText} start the same way: invisible formatting characters
 * are dropped, NFKD pulls ligatures, full-width letters and accents apart, the
 * combining marks that leaves behind are dropped, the text is lower cased, and
 * the Cyrillic and Greek letters drawn like Latin ones are mapped onto the
 * letter they imitate.</p>
 *
 * <p>They part company over leetspeak. The normalized form keeps digits and
 * punctuation as themselves, so it still reads as what was written and an
 * expression using {@code \b} or matching a number behaves as its author
 * expects. The squeezed form is the suspicious reading: digits and the
 * punctuation people substitute for letters become those letters, and every
 * separator is dropped. It is what the word list is matched against, it is
 * never shown to anyone, and it is allowed to be nonsense — {@code World!!}
 * reads as {@code worldii} there, which no rule is written against.</p>
 */
public final class TextNormalizer {

    /**
     * Letters from other alphabets drawn like Latin ones. Mapped in both forms:
     * a Cyrillic {@code а} in an English sentence is never innocent, and unlike
     * a digit it carries no meaning of its own.
     */
    private static final Map<Character, Character> LOOK_ALIKES = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('с', 'c'), Map.entry('е', 'e'),
            Map.entry('н', 'h'), Map.entry('і', 'i'), Map.entry('ј', 'j'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('ѕ', 's'),
            Map.entry('т', 't'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('ѡ', 'w'),
            Map.entry('α', 'a'), Map.entry('β', 'b'), Map.entry('ε', 'e'), Map.entry('ι', 'i'),
            Map.entry('κ', 'k'), Map.entry('ο', 'o'), Map.entry('ρ', 'p'), Map.entry('τ', 't'),
            Map.entry('υ', 'u'), Map.entry('χ', 'x'), Map.entry('γ', 'y'), Map.entry('ν', 'v'));

    /**
     * Digits and punctuation used as letters. Mapped in the squeezed form only:
     * a message about {@code 1337} or ending in {@code !!} means something
     * there, and nothing there is meant to be read.
     */
    private static final Map<Character, Character> LEETSPEAK = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('6', 'g'), Map.entry('7', 't'), Map.entry('8', 'b'),
            Map.entry('9', 'g'), Map.entry('@', 'a'), Map.entry('$', 's'), Map.entry('!', 'i'),
            Map.entry('|', 'i'), Map.entry('£', 'l'), Map.entry('€', 'e'), Map.entry('+', 't'));

    private TextNormalizer() {
    }

    /** Rewrites {@code text} into the forms the filters match against. */
    public static NormalizedText normalize(String text) {
        String folded = fold(text);
        StringBuilder normalized = new StringBuilder(folded.length());
        StringBuilder squeezed = new StringBuilder(folded.length());

        // One pass building both forms, so they cannot drift apart.
        boolean pendingSpace = false;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            Character leet = LEETSPEAK.get(c);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && !normalized.isEmpty()) {
                    normalized.append(' ');
                }
                pendingSpace = false;
                normalized.append(c);
                squeezed.append(leet == null ? c : leet);
            } else if (leet != null) {
                // Punctuation standing in for a letter: a letter to the
                // suspicious reading, a gap to the honest one.
                pendingSpace = true;
                squeezed.append(leet);
            } else {
                // Runs of punctuation and whitespace alike collapse to one gap,
                // and a gap is only written once a letter follows it.
                pendingSpace = true;
            }
        }
        return new NormalizedText(text, normalized.toString(), squeezed.toString());
    }

    /**
     * Drops what is invisible, decomposes, drops the marks, lower cases and maps
     * the look-alikes. Separators are left in place for
     * {@link #normalize(String)} to collapse.
     */
    private static String fold(String text) {
        String decomposed = Normalizer.normalize(stripInvisible(text), Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (isCombiningMark(c)) {
                continue;
            }
            Character replacement = LOOK_ALIKES.get(c);
            out.append(replacement == null ? c : replacement);
        }
        return out.toString();
    }

    /**
     * Removes characters that take up no space on screen, so a word broken up
     * with zero-width spaces reads as one word. Format characters (Cf) cover
     * the zero-width and directional marks, and the soft hyphen; the rest are
     * the invisible operators and the Mongolian vowel separator, which are not
     * Cf. Control characters become a space rather than vanishing, so a line
     * break cannot glue two words into one.
     */
    private static String stripInvisible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL) {
                out.append(' ');
                return;
            }
            if (type == Character.FORMAT || codePoint == 0x180E
                    || (codePoint >= 0x2060 && codePoint <= 0x2064)) {
                return;
            }
            out.appendCodePoint(codePoint);
        });
        return out.toString();
    }

    private static boolean isCombiningMark(char c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}
