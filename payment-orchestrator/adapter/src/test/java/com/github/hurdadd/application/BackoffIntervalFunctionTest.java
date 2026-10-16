package com.github.hurdadd.application;

import io.github.resilience4j.core.IntervalFunction;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PAY-202: backoff waits must grow between retries — jitter included.
 *
 * <p>The n-th wait is {@code base × multiplier^(n−1) × random[1−j .. 1+j]},
 * so with the production values (100 ms, 2, 0.5):
 *
 * <pre>
 * wait(1) ∈ [50 .. 150] ms
 * wait(2) ∈ [100 .. 300] ms
 * wait(3) ∈ [200 .. 600] ms
 * </pre>
 *
 * <p>Two properties hold and are asserted:
 * <ol>
 *   <li><b>Every sample lands inside its own attempt's range.</b> The exponential
 *       backbone (×2 per attempt) is what makes the ranges — and therefore the
 *       waits — grow. A regression to a shrinking multiplier (the original
 *       PAY-202 bug: 0.2) puts attempt-2 samples near 20 ms and fails this test.</li>
 *   <li><b>The ranges ascend.</b> Individual samples may overlap between
 *       neighbouring attempts ([50..150] vs [100..300]) — that overlap is the
 *       jitter doing its job: spreading simultaneous retriers so they do not
 *       stampede as one synchronized wave (thundering herd). The growth is in
 *       the backbone, not guaranteed sample-by-sample.</li>
 * </ol>
 *
 * <p>Constants mirror {@code application.yml} ({@code psp.resilience}); tests
 * spell values out instead of reading the file, so a broken yml fails boot,
 * not the tests.
 */
class BackoffIntervalFunctionTest {

    private static final long BASE_MS = 100;
    private static final double MULTIPLIER = 2.0;
    private static final double JITTER = 0.5;
    private static final int SAMPLES_PER_ATTEMPT = 200;

    private final IntervalFunction backoff =
            IntervalFunction.ofExponentialRandomBackoff(
                    Duration.ofMillis(BASE_MS), MULTIPLIER, JITTER);

    @Test
    void everySampleStaysInsideItsOwnAttemptRange() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Range range = rangeFor(attempt);
            for (int sample = 0; sample < SAMPLES_PER_ATTEMPT; sample++) {
                long waitMs = backoff.apply(attempt);
                assertTrue(range.contains(waitMs),
                        "attempt " + attempt + ": " + waitMs + " ms outside " + range);
            }
        }
    }

    @Test
    void rangesAscendWithJitterTakenIntoAccount() {
        Range first = rangeFor(1);
        Range second = rangeFor(2);
        Range third = rangeFor(3);

        assertTrue(second.minMs() >= first.minMs() && second.maxMs() > first.maxMs(),
                second + " must ascend over " + first);
        assertTrue(third.minMs() >= second.minMs() && third.maxMs() > second.maxMs(),
                third + " must ascend over " + second);
    }

    @Test
    void theBackboneIsDeterministicallyAscending() {
        // The jitter-free backbone proves where the growth comes from:
        // exactly base × 2^(n−1), strictly ascending, no dice involved.
        IntervalFunction backbone =
                IntervalFunction.ofExponentialBackoff(Duration.ofMillis(BASE_MS), MULTIPLIER);

        long previous = 0;
        for (int attempt = 1; attempt <= 4; attempt++) {
            long waitMs = backbone.apply(attempt);
            assertTrue(waitMs > previous, "backbone wait must strictly grow between attempts");
            assertEquals(BASE_MS * (long) Math.pow(2, attempt - 1), waitMs,
                    "backbone wait for attempt " + attempt);
            previous = waitMs;
        }
    }

    private static Range rangeFor(int attempt) {
        long backbone = Math.round(BASE_MS * Math.pow(MULTIPLIER, attempt - 1));
        return new Range(
                Math.round(backbone * (1 - JITTER)),
                Math.round(backbone * (1 + JITTER)));
    }

    private record Range(long minMs, long maxMs) {

        boolean contains(long waitMs) {
            return waitMs >= minMs && waitMs <= maxMs;
        }

        @Override
        public String toString() {
            return "[" + minMs() + ".." + maxMs() + "] ms";
        }
    }
}
