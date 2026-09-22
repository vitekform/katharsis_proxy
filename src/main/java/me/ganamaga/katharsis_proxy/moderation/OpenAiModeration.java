package me.ganamaga.katharsis_proxy.moderation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.ganamaga.katharsis_proxy.util.DebugLog;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The online layer: OpenAI's moderation model, asked about messages the word
 * list and the expressions had nothing to say about.
 *
 * <p>The quota is counted before anything is sent, by {@link RateLimitBudget},
 * so a busy evening spends the day's requests on messages rather than on 429s.
 * Being out of quota is not an error and not a finding: the message is reported
 * as {@link Outcome#SKIPPED} and the caller lets it through.</p>
 *
 * <p>The original text is sent, not the normalized form: the model reads
 * obfuscation better than a word list does, and folding {@code 1337} down to
 * letters would only take context away from it.</p>
 *
 * <p>Every call is written to {@code debug.log} when that is turned on: what
 * was sent, what came back and how long it took, which is what an admin needs
 * to tell a refused key from a slow one from a message the model simply did not
 * object to. The API key is never written, the text is.</p>
 */
public final class OpenAiModeration {

    /** What came of asking, or of not being able to. */
    public enum Outcome {

        /** The model saw nothing wrong. */
        CLEAN,

        /** The model objected; {@link Answer#categories()} says to what. */
        FLAGGED,

        /** Nothing was sent: the quota for the minute or the day is spent. */
        SKIPPED,

        /** OpenAI answered 429 even so. Treated like {@link #SKIPPED}. */
        RATE_LIMITED,

        /** The call failed, timed out, or came back as something unreadable. */
        ERROR
    }

    /**
     * @param outcome    what happened
     * @param categories the categories the model objected to, empty otherwise
     * @param detail     why an {@link Outcome#ERROR} happened, for the log
     */
    public record Answer(Outcome outcome, List<String> categories, String detail) {

        static final Answer CLEAN = new Answer(Outcome.CLEAN, List.of(), "");
        static final Answer SKIPPED = new Answer(Outcome.SKIPPED, List.of(), "");

        static Answer error(String detail) {
            return new Answer(Outcome.ERROR, List.of(), detail);
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Rough characters per token, used only to keep the per-minute token budget
     * honest. Overestimating costs nothing but a slightly smaller budget.
     */
    private static final int CHARS_PER_TOKEN = 3;

    /** Charged on top of the text, for the request's own structure. */
    private static final int TOKEN_OVERHEAD = 8;

    /** How much of a message or an answer reaches {@code debug.log}. */
    private static final int DEBUG_CHARS = 1_000;

    /** How much of a failing answer reaches the error detail, and the console. */
    private static final int DETAIL_CHARS = 200;

    private final Logger logger;
    private final DebugLog debug;
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final RateLimitBudget budget;
    private final Set<String> categories;
    private final double scoreThreshold;

    /** Numbers the calls, so a request and its answer can be paired in the
     * debug log even while several messages are checked at once. */
    private final AtomicLong calls = new AtomicLong();

    /**
     * @param debug          where each call is recorded, or
     *                       {@link DebugLog#DISABLED} to record nothing
     * @param categories     the only categories acted on, or empty for all of them
     * @param scoreThreshold flag when a category scores at least this, even if
     *                       the model itself did not; 0 or less leaves the
     *                       decision entirely to the model
     */
    public OpenAiModeration(Logger logger, DebugLog debug, HttpClient http, URI endpoint, String apiKey,
                            String model, Duration timeout, RateLimitBudget budget, Collection<String> categories,
                            double scoreThreshold) {
        this.logger = logger;
        this.debug = debug == null ? DebugLog.DISABLED : debug;
        this.http = http;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.budget = budget;
        this.categories = categories == null ? Set.of() : categories.stream()
                .filter(category -> category != null && !category.isBlank())
                .map(category -> category.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.scoreThreshold = scoreThreshold;
    }

    /** The budget this client spends, so callers can report what is left. */
    public RateLimitBudget budget() {
        return budget;
    }

    /**
     * Asks the model about {@code text}, blocking until it answers or the
     * timeout passes. Never throws: everything that can go wrong comes back as
     * an {@link Outcome}, because the caller has to decide what to do with the
     * message either way.
     */
    public Answer check(String text) {
        long call = calls.incrementAndGet();
        int tokens = estimateTokens(text);
        if (!budget.tryAcquire(tokens)) {
            debug.log("openai call={} skipped model={} chars={} tokens={} remaining-today={} reason=out-of-quota",
                    call, model, text.length(), tokens, budget.remainingToday());
            return Answer.SKIPPED;
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        debug.log("openai call={} request model={} url={} chars={} tokens={} timeout={}ms text=\"{}\"",
                call, model, endpoint, text.length(), tokens, timeout.toMillis(), abbreviate(text, DEBUG_CHARS));

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            debug.log("openai call={} answer in={}ms outcome=ERROR error={}", call, millisSince(startedAt), e);
            return Answer.error(e.toString());
        } catch (InterruptedException e) {
            // The proxy is shutting down. Nothing is decided, and saying so lets
            // the caller hold the message back rather than pass it unchecked.
            Thread.currentThread().interrupt();
            debug.log("openai call={} answer in={}ms outcome=ERROR error=interrupted", call, millisSince(startedAt));
            return Answer.error("interrupted");
        }
        long elapsedMs = millisSince(startedAt);

        if (response.statusCode() == 429) {
            Duration retryAfter = retryAfter(response);
            budget.rateLimited(retryAfter);
            debug.log("openai call={} answer status=429 in={}ms outcome=RATE_LIMITED retry-after={}",
                    call, elapsedMs, retryAfter == null ? "unstated" : retryAfter.toSeconds() + "s");
            logger.warn("OpenAI rate limited the moderation request; messages are not checked by the model "
                    + "until the limit clears.");
            return new Answer(Outcome.RATE_LIMITED, List.of(), "");
        }
        if (response.statusCode() != 200) {
            debug.log("openai call={} answer status={} in={}ms outcome=ERROR body={}",
                    call, response.statusCode(), elapsedMs, abbreviate(response.body(), DEBUG_CHARS));
            return Answer.error("HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body(), DETAIL_CHARS));
        }

        Answer answer = read(response.body());
        debug.log("openai call={} answer status=200 in={}ms outcome={} categories={} detail={} body={}",
                call, elapsedMs, answer.outcome(), answer.categories(), answer.detail(),
                abbreviate(response.body(), DEBUG_CHARS));
        return answer;
    }

    /** How long a call took, for the debug log. */
    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** Reads the answer, deciding what counts as flagged. */
    private Answer read(String body) {
        JsonObject result;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return Answer.error("the answer is not a JSON object");
            }
            JsonElement results = parsed.getAsJsonObject().get("results");
            if (results == null || !results.isJsonArray() || results.getAsJsonArray().isEmpty()) {
                return Answer.error("the answer carries no results");
            }
            JsonElement first = results.getAsJsonArray().get(0);
            if (!first.isJsonObject()) {
                return Answer.error("the first result is not a JSON object");
            }
            result = first.getAsJsonObject();
        } catch (JsonParseException e) {
            return Answer.error("the answer is not valid JSON");
        }

        List<String> objections = new ArrayList<>();
        JsonObject flags = object(result, "categories");
        boolean modelFlagged = result.has("flagged") && result.get("flagged").isJsonPrimitive()
                && result.get("flagged").getAsBoolean();
        if (modelFlagged && flags != null) {
            for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsBoolean()
                        && isWatched(entry.getKey())) {
                    objections.add(entry.getKey());
                }
            }
        }

        if (scoreThreshold > 0.0d) {
            JsonObject scores = object(result, "category_scores");
            if (scores != null) {
                for (Map.Entry<String, JsonElement> entry : scores.entrySet()) {
                    if (!isWatched(entry.getKey()) || objections.contains(entry.getKey())) {
                        continue;
                    }
                    if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsDouble() >= scoreThreshold) {
                        objections.add(entry.getKey());
                    }
                }
            }
        }

        if (objections.isEmpty()) {
            return Answer.CLEAN;
        }
        objections.sort(String::compareTo);
        return new Answer(Outcome.FLAGGED, List.copyOf(objections), "");
    }

    /** Whether the server acts on this category at all. */
    private boolean isWatched(String category) {
        return categories.isEmpty() || categories.contains(category.toLowerCase(Locale.ROOT));
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** How long OpenAI asked the proxy to wait, or {@code null} if it did not say. */
    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .map(value -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(value.strip()));
                    } catch (NumberFormatException e) {
                        // A date rather than seconds. Waiting a minute is close enough.
                        return null;
                    }
                })
                .orElse(null);
    }

    /** What the text is charged against the per-minute token budget. */
    static int estimateTokens(String text) {
        return TOKEN_OVERHEAD + (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Keeps an answer from filling the log with an HTML page. */
    private static String abbreviate(String body, int limit) {
        if (body == null) {
            return "";
        }
        String single = body.replaceAll("\\s+", " ").strip();
        return single.length() <= limit ? single : single.substring(0, limit) + "…";
    }
}
