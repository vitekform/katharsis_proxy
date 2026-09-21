package me.ganamaga.katharsis_proxy.moderation;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Runs a chat message through the filters in order and says whether it may be
 * passed on: normalize, then the configured rules, then OpenAI for what is left.
 *
 * <p>The layers are ordered by what they cost. Normalizing and matching happen
 * in memory and catch the obvious, which keeps the daily OpenAI quota for the
 * messages that actually need a model to judge them.</p>
 *
 * <h2>When the model cannot answer</h2>
 *
 * <p>Two failures look alike and are treated as opposites on purpose. Being out
 * of quota, whether the budget here says so or OpenAI answers 429, lets the
 * message through: the limit is reached in normal use, and chat must not stop
 * working every evening. Anything else — a timeout, a refused key, an
 * unreadable answer — holds the message back, because that is a fault to fix
 * rather than a state to live in, and an unchecked message is exactly what this
 * exists to prevent.</p>
 *
 * <p>Held-back messages are flagged {@link ModerationSource#UNAVAILABLE}, which
 * says no more than that nothing could judge them. They are not a finding
 * against the player.</p>
 */
public final class ChatModerator {

    /** How often a failing moderation call is allowed to write a full warning. */
    private static final Duration COMPLAINT_INTERVAL = Duration.ofMinutes(1L);

    private final Logger logger;
    private final Clock clock;
    private final RuleFilter rules;

    /** Null when the model layer is off or has no key to use. */
    private final OpenAiModeration openAi;

    private volatile Instant complainedAt = Instant.MIN;

    public ChatModerator(Logger logger, RuleFilter rules, OpenAiModeration openAi) {
        this(logger, Clock.systemUTC(), rules, openAi);
    }

    ChatModerator(Logger logger, Clock clock, RuleFilter rules, OpenAiModeration openAi) {
        this.logger = logger;
        this.clock = clock;
        this.rules = rules;
        this.openAi = openAi;
    }

    /** Whether the model layer is in use, as opposed to the rules alone. */
    public boolean usesModel() {
        return openAi != null;
    }

    /** How many configured rules are loaded, words and expressions together. */
    public int ruleCount() {
        return rules.size();
    }

    /**
     * Judges {@code content}. Blocks for as long as the model takes, so it must
     * be called off the event thread.
     */
    public ModerationVerdict check(String content) {
        NormalizedText text = TextNormalizer.normalize(content);
        if (text.isBlank()) {
            // Punctuation and emoji only: there is no word here to match, and
            // the model has nothing to read either.
            return ModerationVerdict.clean();
        }

        ModerationVerdict fromRules = rules.check(text);
        if (fromRules.isFlagged()) {
            return fromRules;
        }
        if (openAi == null) {
            return ModerationVerdict.clean();
        }

        OpenAiModeration.Answer answer = openAi.check(text.original());
        return switch (answer.outcome()) {
            case CLEAN -> ModerationVerdict.clean();
            case FLAGGED -> ModerationVerdict.flagged(ModerationSource.OPENAI, String.join(", ", answer.categories()));
            case SKIPPED, RATE_LIMITED -> {
                // Deliberately open: the quota runs out in ordinary use.
                logger.debug("Let a chat message through unchecked by the model: the OpenAI quota is spent.");
                yield ModerationVerdict.clean();
            }
            case ERROR -> {
                complain(answer.detail());
                yield ModerationVerdict.flagged(ModerationSource.UNAVAILABLE, answer.detail());
            }
        };
    }

    /**
     * Reports a failing moderation call, at most once a minute. An outage
     * affects every message, and one warning per message would bury the log
     * under the very thing an admin needs to read.
     */
    private void complain(String detail) {
        Instant now = clock.instant();
        if (now.isBefore(complainedAt.plus(COMPLAINT_INTERVAL))) {
            logger.debug("The OpenAI moderation call failed again: {}", detail);
            return;
        }
        complainedAt = now;
        logger.warn("The OpenAI moderation call failed, so chat is being held back until it works again: {}", detail);
    }
}
