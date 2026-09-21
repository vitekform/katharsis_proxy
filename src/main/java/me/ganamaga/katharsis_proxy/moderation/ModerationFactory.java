package me.ganamaga.katharsis_proxy.moderation;

import me.ganamaga.katharsis_proxy.config.ModerationConfig;
import me.ganamaga.katharsis_proxy.config.OpenAiConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Builds the moderator described by {@code config.yml}, reporting at startup
 * what will and will not be checked.
 *
 * <p>The configuration is read once, when the proxy starts, unlike the Discord
 * channel maps: compiling a word list on every chat message would be wasteful,
 * and rules are not changed mid-evening.</p>
 */
public final class ModerationFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5L);

    private ModerationFactory() {
    }

    /**
     * @return the moderator, or {@code null} when moderation is off or has
     *         nothing configured to check with, in which case chat is relayed
     *         and logged exactly as it was before moderation existed
     */
    public static ChatModerator create(Logger logger, ModerationConfig config) {
        if (!config.enabled()) {
            logger.info("Chat moderation is off; every reported message is relayed and logged.");
            return null;
        }

        RuleFilter rules = RuleFilter.compile(logger, config.words(), config.patterns(), config.allow());
        OpenAiModeration openAi = openAi(logger, config.openai());
        if (rules.isEmpty() && openAi == null) {
            logger.warn("Chat moderation is on but no words, patterns or OpenAI key are configured; "
                    + "nothing will be checked.");
            return null;
        }

        logger.info("Chat moderation is on with {} rule(s){}.", rules.size(),
                openAi == null ? " and no model" : " and the " + config.openai().model() + " model");
        return new ChatModerator(logger, rules, openAi);
    }

    /** @return the model layer, or {@code null} when it is off or unusable */
    private static OpenAiModeration openAi(Logger logger, OpenAiConfig config) {
        if (!config.enabled()) {
            return null;
        }
        if (config.apiKey().isEmpty()) {
            logger.warn("moderation.openai is on but its api-key is empty; only the configured rules will run.");
            return null;
        }
        URI endpoint;
        try {
            endpoint = URI.create(config.url());
        } catch (IllegalArgumentException e) {
            logger.warn("moderation.openai.url is not a valid URL; only the configured rules will run.", e);
            return null;
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        RateLimitBudget budget = new RateLimitBudget(Clock.systemUTC(),
                config.requestsPerMinute(), config.tokensPerMinute(), config.requestsPerDay());
        return new OpenAiModeration(logger, http, endpoint, config.apiKey(), config.model(),
                Duration.ofMillis(Math.max(500L, config.timeoutMs())), budget,
                config.categories(), config.scoreThreshold());
    }
}
