package me.ganamaga.katharsis_proxy.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quota has to be counted here, before OpenAI starts refusing. */
class RateLimitBudgetTest {

    private static final Instant START = Instant.parse("2026-09-21T12:00:00Z");

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {

        private Instant now = START;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    @DisplayName("stops at the per-minute request limit and starts again the next minute")
    void countsRequestsPerMinute() {
        TestClock clock = new TestClock();
        RateLimitBudget budget = new RateLimitBudget(clock, 2, 0, 0);

        assertTrue(budget.tryAcquire(1));
        assertTrue(budget.tryAcquire(1));
        assertFalse(budget.tryAcquire(1));
        assertTrue(budget.isExhausted());

        clock.advance(Duration.ofSeconds(61L));
        assertFalse(budget.isExhausted());
        assertTrue(budget.tryAcquire(1));
    }

    @Test
    @DisplayName("stops at the per-minute token limit without spending a request")
    void countsTokensPerMinute() {
        TestClock clock = new TestClock();
        RateLimitBudget budget = new RateLimitBudget(clock, 0, 100, 0);

        assertTrue(budget.tryAcquire(90));
        assertFalse(budget.tryAcquire(20));
        // The refused call took nothing, so a smaller one still fits.
        assertTrue(budget.tryAcquire(10));
        assertFalse(budget.tryAcquire(1));
    }

    @Test
    @DisplayName("stops at the daily request limit, which a new minute does not reset")
    void countsRequestsPerDay() {
        TestClock clock = new TestClock();
        RateLimitBudget budget = new RateLimitBudget(clock, 0, 0, 3);

        assertTrue(budget.tryAcquire(1));
        assertTrue(budget.tryAcquire(1));
        clock.advance(Duration.ofHours(5L));
        assertEquals(1, budget.remainingToday());
        assertTrue(budget.tryAcquire(1));
        assertFalse(budget.tryAcquire(1));
        assertEquals(0, budget.remainingToday());

        clock.advance(Duration.ofHours(20L));
        assertTrue(budget.tryAcquire(1));
    }

    @Test
    @DisplayName("holds everything back for as long as OpenAI asked")
    void honoursRetryAfter() {
        TestClock clock = new TestClock();
        RateLimitBudget budget = new RateLimitBudget(clock, 500, 0, 0);

        budget.rateLimited(Duration.ofSeconds(30L));
        assertTrue(budget.isExhausted());
        assertFalse(budget.tryAcquire(1));

        clock.advance(Duration.ofSeconds(29L));
        assertFalse(budget.tryAcquire(1));

        clock.advance(Duration.ofSeconds(2L));
        assertTrue(budget.tryAcquire(1));
    }

    @Test
    @DisplayName("waits a minute when OpenAI did not say how long")
    void defaultsTheBackoff() {
        TestClock clock = new TestClock();
        RateLimitBudget budget = new RateLimitBudget(clock, 500, 0, 0);

        budget.rateLimited(null);
        clock.advance(Duration.ofSeconds(59L));
        assertFalse(budget.tryAcquire(1));
        clock.advance(Duration.ofSeconds(2L));
        assertTrue(budget.tryAcquire(1));
    }

    @Test
    @DisplayName("counts no limit that is set to zero")
    void ignoresUnsetLimits() {
        TestClock clock = new TestClock();
        RateLimitBudget budget = new RateLimitBudget(clock, 0, 0, 0);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(budget.tryAcquire(1_000));
        }
        assertEquals(Integer.MAX_VALUE, budget.remainingToday());
    }
}
