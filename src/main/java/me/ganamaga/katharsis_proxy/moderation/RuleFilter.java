package me.ganamaga.katharsis_proxy.moderation;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The offline layer: the configured word list and regular expressions, matched
 * against a {@link NormalizedText}.
 *
 * <p>Words from {@code moderation.words} are matched against the squeezed form,
 * where the separators are gone, and are compiled so that stretching a word out
 * does not hide it: repeated letters in the term are collapsed and every letter
 * is allowed to repeat, so {@code nigger} becomes {@code n+i+g+e+r+} and catches
 * {@code niiigerrr} and {@code n i g g e r} alike.</p>
 *
 * <p>Because the squeezed form has no word boundaries left, a short term is a
 * substring of innocent words — the Scunthorpe problem. {@code moderation.allow}
 * is the answer: a match that falls inside an allowed term is not a match.</p>
 *
 * <p>Expressions from {@code moderation.patterns} are used as written, against
 * the normalized form, which still has single spaces between words so
 * {@code \b} works, and against the original text, so a rule can look for
 * something normalization would have destroyed such as a URL. The allow list
 * does not apply to them; an expression says for itself what it matches.</p>
 */
public final class RuleFilter {

    /** A compiled rule and the name it is reported under. */
    private record Rule(String label, Pattern pattern) {
    }

    private final List<Rule> words;
    private final List<Rule> patterns;
    private final List<Pattern> allowed;

    private RuleFilter(List<Rule> words, List<Rule> patterns, List<Pattern> allowed) {
        this.words = words;
        this.patterns = patterns;
        this.allowed = allowed;
    }

    /** A filter that never matches, for when the rule layer is switched off. */
    public static RuleFilter empty() {
        return new RuleFilter(List.of(), List.of(), List.of());
    }

    /**
     * Compiles the configured rules. Terms and expressions that cannot be
     * compiled are logged and skipped, so one bad line does not cost the
     * server every other rule.
     */
    public static RuleFilter compile(Logger logger, List<String> words, List<String> patterns, List<String> allow) {
        List<Rule> compiledWords = new ArrayList<>();
        for (String word : words == null ? List.<String>of() : words) {
            Pattern pattern = stretchTolerant(word);
            if (pattern != null) {
                compiledWords.add(new Rule(word.strip(), pattern));
            }
        }

        List<Rule> compiledPatterns = new ArrayList<>();
        for (String expression : patterns == null ? List.<String>of() : patterns) {
            if (expression == null || expression.isBlank()) {
                continue;
            }
            try {
                compiledPatterns.add(new Rule(expression.strip(),
                        Pattern.compile(expression.strip(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
            } catch (PatternSyntaxException e) {
                logger.warn("Skipped moderation.patterns entry '{}': it is not a valid regular expression ({}).",
                        expression, e.getDescription());
            }
        }

        List<Pattern> compiledAllow = new ArrayList<>();
        for (String term : allow == null ? List.<String>of() : allow) {
            Pattern pattern = stretchTolerant(term);
            if (pattern != null) {
                compiledAllow.add(pattern);
            }
        }
        return new RuleFilter(List.copyOf(compiledWords), List.copyOf(compiledPatterns), List.copyOf(compiledAllow));
    }

    /** Whether any rule at all is loaded. */
    public boolean isEmpty() {
        return words.isEmpty() && patterns.isEmpty();
    }

    /** How many rules are loaded, words and expressions together. */
    public int size() {
        return words.size() + patterns.size();
    }

    /**
     * Matches {@code text} against every rule.
     *
     * @return a verdict flagged by {@link ModerationSource#RULES} naming the
     *         first rule that matched, or {@link ModerationVerdict#clean()}
     */
    public ModerationVerdict check(NormalizedText text) {
        for (Rule rule : words) {
            Matcher matcher = rule.pattern().matcher(text.squeezed());
            while (matcher.find()) {
                if (!isAllowed(text.squeezed(), matcher.start(), matcher.end())) {
                    return ModerationVerdict.flagged(ModerationSource.RULES, "word: " + rule.label());
                }
            }
        }
        for (Rule rule : patterns) {
            if (rule.pattern().matcher(text.normalized()).find()
                    || rule.pattern().matcher(text.original()).find()) {
                return ModerationVerdict.flagged(ModerationSource.RULES, "pattern: " + rule.label());
            }
        }
        return ModerationVerdict.clean();
    }

    /** Whether the match from {@code start} to {@code end} sits inside an allowed term. */
    private boolean isAllowed(String squeezed, int start, int end) {
        for (Pattern pattern : allowed) {
            Matcher matcher = pattern.matcher(squeezed);
            while (matcher.find()) {
                if (matcher.start() <= start && matcher.end() >= end) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Compiles a literal term into an expression that also matches it stretched
     * out or with its letters doubled, by collapsing the term's own repeats and
     * letting every character repeat.
     *
     * @return the pattern, or {@code null} if the term held nothing to match
     */
    private static Pattern stretchTolerant(String term) {
        if (term == null) {
            return null;
        }
        // Only what survives normalization can ever be matched, so the term is
        // put through the very same folding as the message.
        NormalizedText normalized = TextNormalizer.normalize(term);
        // A term holding no word at all, such as "!!!", would otherwise compile
        // into a rule matching the letter its punctuation stands for, and flag
        // every message containing that letter.
        if (normalized.isBlank()) {
            return null;
        }
        String folded = normalized.squeezed();
        StringBuilder expression = new StringBuilder(folded.length() * 4);
        char previous = 0;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c == previous) {
                continue;
            }
            previous = c;
            expression.append(Pattern.quote(String.valueOf(c))).append('+');
        }
        return Pattern.compile(expression.toString());
    }
}
