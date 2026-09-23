package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentReconcilerTest {

    static class InMemoryPaymentRepository implements PaymentRepository {
        final Map<UUID, Payment> byId = new HashMap<>();

        void seed(Payment payment) {
            byId.put(payment.getPaymentId().getValue(), payment);
        }

        @Override
        public Payment save(Payment payment) {
            byId.put(payment.getPaymentId().getValue(), payment);
            return payment;
        }

        @Override
        public Optional<Payment> findById(PaymentId paymentId) {
            return Optional.ofNullable(byId.get(paymentId.getValue()));
        }

        @Override
        public Optional<Payment> findByPspReference(PspReference pspReference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Payment> findStuckPending(Instant olderThan, Instant newerThan) {
            return byId.values().stream()
                    .filter(p -> p.getPaymentStatus() == PaymentStatus.PENDING_PSP)
                    .filter(p -> !p.getCreatedAt().isAfter(olderThan))
                    .filter(p -> !p.getCreatedAt().isBefore(newerThan))
                    .toList();
        }
    }

    static class StubPspClient implements PspClient {
        private final PspOutcome outcomeToReturn;

        StubPspClient(PspOutcome outcomeToReturn) {
            this.outcomeToReturn = outcomeToReturn;
        }

        @Override
        public PspResult authorize(AuthorizationRequest dto) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PspResult checkStatus(PspReference reference) {
            return new PspResult(outcomeToReturn, reference);
        }
    }

    private Payment stuckPayment(Instant createdAt, PspReference ref) {
        Payment payment = Payment.create(new Money(new BigDecimal("50.00"), Currency.USD),
                MerchantId.from(UUID.randomUUID()));
        payment.markPending(ref);
        return Payment.reconstitute(payment.getMoney(), payment.getPaymentId(), payment.getMerchantId(),
                PaymentStatus.PENDING_PSP, createdAt, 0, ref);
    }

    /**
     * Golden-path scenario from PAY-4: the PSP actually authorized the payment but its callback
     * never arrived. Maximum window before the reconciler catches it: stuckThreshold (how old a
     * PENDING_PSP payment must be before we consider polling it) + poll-interval (how often the
     * scheduled job runs) — worst case the payment crosses the threshold moments after a run just
     * finished, so it waits almost one full extra interval for the next run.
     * With stuckThreshold=2m and poll-interval=30s (this project's configured values), the maximum
     * time-to-detection is 2m + 30s = 150s.
     */
    @Test
    void payment_authorized_at_psp_but_callback_never_arrived_is_reconciled_to_authorized() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        Duration stuckThreshold = Duration.ofMinutes(2);
        Duration maxAge = Duration.ofHours(24);

        PspReference ref = new PspReference("psp-ref-lost-callback");
        Instant createdAt = clock.instant().minus(stuckThreshold).minusSeconds(1); // just past the threshold
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(stuckPayment(createdAt, ref));

        PspClient pspSaysApproved = new StubPspClient(PspOutcome.APPROVED);
        PaymentReconciler reconciler = new PaymentReconciler(repository, pspSaysApproved, clock, stuckThreshold, maxAge);

        reconciler.reconcile();

        Payment reconciled = repository.byId.values().iterator().next();
        assertEquals(PaymentStatus.AUTHORIZED, reconciled.getPaymentStatus());
    }

    @Test
    void payment_not_yet_past_stuck_threshold_is_left_alone() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        Duration stuckThreshold = Duration.ofMinutes(2);
        Duration maxAge = Duration.ofHours(24);

        PspReference ref = new PspReference("psp-ref-too-fresh");
        Instant createdAt = clock.instant().minusSeconds(30); // younger than the 2-minute threshold
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(stuckPayment(createdAt, ref));

        PaymentReconciler reconciler = new PaymentReconciler(repository, new StubPspClient(PspOutcome.APPROVED), clock, stuckThreshold, maxAge);
        reconciler.reconcile();

        assertEquals(PaymentStatus.PENDING_PSP, repository.byId.values().iterator().next().getPaymentStatus());
    }

    @Test
    void psp_still_not_knowing_leaves_payment_pending_for_next_run() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        Duration stuckThreshold = Duration.ofMinutes(2);
        Duration maxAge = Duration.ofHours(24);

        PspReference ref = new PspReference("psp-ref-timeout");
        Instant createdAt = clock.instant().minus(stuckThreshold).minusSeconds(1);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(stuckPayment(createdAt, ref));

        PaymentReconciler reconciler = new PaymentReconciler(repository, new StubPspClient(PspOutcome.TIMEOUT), clock, stuckThreshold, maxAge);
        reconciler.reconcile();

        assertEquals(PaymentStatus.PENDING_PSP, repository.byId.values().iterator().next().getPaymentStatus());
    }
}
