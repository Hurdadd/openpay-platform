package com.github.hurdadd.application;

import com.github.hurdadd.adapter.psp.PspResilienceSettings;
import com.github.hurdadd.adapter.psp.ResilientPspClient;
import com.github.hurdadd.adapter.psp.exception.PspClientException;
import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantReference;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.PspReference;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PAY-202: the two-operation retry policy, proven by counting real calls.
 *
 * <p>authorize   = {@code CircuitBreaker(TimeLimiter(call))} — one attempt, ever:
 * a PSP timeout means <em>unknown</em>, and re-sending money without a PSP-side
 * idempotency key can charge twice.
 * checkStatus = {@code Retry(CircuitBreaker(TimeLimiter(call)))} — a read is
 * idempotent, so retries are free.
 *
 * <p>Call counts are the assertion: they see through any exception hierarchy,
 * because what a payment system cares about is how many times money was moved.
 */
class ResilientPspClientPolicyTest {

    // Same values as application.yml (psp.resilience); mirrored, not read:
    // a broken yml must fail boot, not the tests.
    private static final PspResilienceSettings SETTINGS = new PspResilienceSettings(
            Duration.ofMillis(1000), 2,
            Duration.ofMillis(100), 2.0, 0.5,
            Duration.ofSeconds(10)
    );

    @Test
    void aClientErrorIsNeverRetriedOnEitherOperation() {
        CountingPsp psp = CountingPsp.withClientErrors();
        ResilientPspClient client = new ResilientPspClient(psp, SETTINGS);

        expectFailure(() -> client.authorize(paymentRequest()));
        expectFailure(() -> client.checkStatus(reference()));

        assertEquals(1, psp.authorizeCalls());
        assertEquals(1, psp.checkStatusCalls());
    }

    @Test
    void tenClientErrorsDoNotTripTheBreaker() {
        CountingPsp psp = CountingPsp.withClientErrors();
        ResilientPspClient client = new ResilientPspClient(psp, SETTINGS);

        for (int i = 0; i < 10; i++) {
            expectFailure(() -> client.authorize(paymentRequest()));
        }

        // Ten declined cards must not take a healthy PSP out of service:
        // a 4xx-class answer is the PSP doing its job, not PSP sickness.
        assertEquals(10, psp.authorizeCalls()); // every decline was asked exactly once
        assertEquals(CircuitBreaker.State.CLOSED, client.getCircuitBreaker().getState());
    }

    @Test
    void aTransientFailureIsRetriedOnlyOnCheckStatus() {
        CountingPsp psp = CountingPsp.withTransientErrors();
        ResilientPspClient client = new ResilientPspClient(psp, SETTINGS);

        expectFailure(() -> client.authorize(paymentRequest()));
        expectFailure(() -> client.checkStatus(reference()));

        assertEquals(1, psp.authorizeCalls());   // money path: no retry, by design
        assertEquals(2, psp.checkStatusCalls());  // read path: one retry (maxAttempts=2)
    }

    private static void expectFailure(Runnable call) {
        try {
            call.run();
        } catch (PspException expected) {
            // the failure itself is expected; the policy under test is the call count
        }
    }

    private static AuthorizationRequest paymentRequest() {
        return new AuthorizationRequest(
                Money.of(BigDecimal.valueOf(100), Currency.USD),
                MerchantReference.of("merchant-123"),
                new com.github.hurdadd.domain.PspReference("psp-ref-test")
        );
    }

    private static PspReference reference() {
        return new PspReference("psp-ref-1");
    }

    /** Counts every call; fails with a 4xx-class or a transient error, never succeeds. */
    private static final class CountingPsp implements PspClient {

        private final boolean clientErrors;
        private final AtomicInteger authorizeCalls = new AtomicInteger();
        private final AtomicInteger checkStatusCalls = new AtomicInteger();

        private CountingPsp(boolean clientErrors) {
            this.clientErrors = clientErrors;
        }

        static CountingPsp withClientErrors() {
            return new CountingPsp(true);
        }

        static CountingPsp withTransientErrors() {
            return new CountingPsp(false);
        }

        @Override
        public PspResult authorize(AuthorizationRequest dto) {
            authorizeCalls.incrementAndGet();
            throw failure("authorize");
        }

        @Override
        public PspResult checkStatus(PspReference pspReference) {
            checkStatusCalls.incrementAndGet();
            throw failure("checkStatus");
        }

        private RuntimeException failure(String operation) {
            return clientErrors
                    ? new PspClientException("4xx-class answer from PSP during " + operation)
                    : new PspException("transient PSP error during " + operation);
        }

        int authorizeCalls() {
            return authorizeCalls.get();
        }

        int checkStatusCalls() {
            return checkStatusCalls.get();
        }
    }
}
