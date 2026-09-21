package me.ganamaga.katharsis_proxy.moderation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps the proxy inside the quota its OpenAI key is given, so requests are
 * held back here rather than spent on answers that come back as 429s.
 *
 * <p>Three windows are counted: requests and tokens per minute, and requests
 * per day. Each is a fixed window that starts when the first request in it is
 * made and resets once it has run out, which is close enough to how the quota
 * actually refills and cannot drift.</p>
 *
 * <p>A 429 that gets through anyway pauses every request until the moment
 * OpenAI asked for, through {@link #rateLimited(Duration)}.</p>
 *
 * <p>Every method is synchronized: several chat messages are moderated at once,
 * and two of them must not both be told there is room for one more.</p>
 */
public final class RateLimitBudget {

    private static final Duration MINUTE = Duration.ofMinutes(1L);
    private static final Duration DAY = Duration.ofDays(1L);

    private final Clock clock;
    private final int requestsPerMinute;
    private final int tokensPerMinute;
    private final int requestsPerDay;

    private Instant minuteStart;
    private int minuteRequests;
    private int minuteTokens;

    private Instant dayStart;
    private int dayRequests;

    /** Set when OpenAI answered 429; nothing is sent before it. */
    private Instant pausedUntil = Instant.MIN;

    /**
     * @param requestsPerMinute RPM for the key, or 0 or less to not count it
     * @param tokensPerMinute   TPM for the key, likewise
     * @param requestsPerDay    RPD for the key, likewise
     */
    public RateLimitBudget(Clock clock, int requestsPerMinute, int tokensPerMinute, int requestsPerDay) {
        this.clock = clock;
        this.requestsPerMinute = requestsPerMinute;
        this.tokensPerMinute = tokensPerMinute;
        this.requestsPerDay = requestsPerDay;
        this.minuteStart = clock.instant();
        this.dayStart = this.minuteStart;
    }

    /**
     * Takes one request and {@code tokens} tokens out of the budget.
     *
     * @return whether there was room; when there was not, nothing was taken and
     *         the caller must not send the request
     */
    public synchronized boolean tryAcquire(int tokens) {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return false;
        }
        roll(now);

        if (exceeds(requestsPerMinute, minuteRequests + 1)
                || exceeds(tokensPerMinute, minuteTokens + tokens)
                || exceeds(requestsPerDay, dayRequests + 1)) {
            return false;
        }
        minuteRequests++;
        minuteTokens += tokens;
        dayRequests++;
        return true;
    }

    /**
     * Records that OpenAI rate limited the proxy anyway, and holds every
     * request back until it said to try again, or for a minute when it did not
     * say. The pause alone is the throttle: spending the minute window on top
     * of it would ignore a {@code Retry-After} shorter than what is left of
     * that window, and keep the proxy quiet longer than OpenAI asked.
     */
    public synchronized void rateLimited(Duration retryAfter) {
        Duration pause = retryAfter == null || retryAfter.isNegative() ? MINUTE : retryAfter;
        Instant until = clock.instant().plus(pause);
        if (until.isAfter(pausedUntil)) {
            pausedUntil = until;
        }
    }

    /** Whether a request would be refused right now, without taking anything. */
    public synchronized boolean isExhausted() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return true;
        }
        roll(now);
        return exceeds(requestsPerMinute, minuteRequests + 1) || exceeds(requestsPerDay, dayRequests + 1);
    }

    /** Requests left in the day's budget, or {@link Integer#MAX_VALUE} when it is not counted. */
    public synchronized int remainingToday() {
        roll(clock.instant());
        return requestsPerDay <= 0 ? Integer.MAX_VALUE : Math.max(0, requestsPerDay - dayRequests);
    }

    /** Starts a fresh window wherever the old one has run out. */
    private void roll(Instant now) {
        if (!now.isBefore(minuteStart.plus(MINUTE))) {
            minuteStart = now;
            minuteRequests = 0;
            minuteTokens = 0;
        }
        if (!now.isBefore(dayStart.plus(DAY))) {
            dayStart = now;
            dayRequests = 0;
        }
    }

    /** A limit of zero or less is not enforced. */
    private static boolean exceeds(int limit, int wanted) {
        return limit > 0 && wanted > limit;
    }
}
