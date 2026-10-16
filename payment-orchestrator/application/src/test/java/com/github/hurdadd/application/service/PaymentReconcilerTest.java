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

    static final com.github.hurdadd.application.port.out.TxRunner DIRECT = new com.github.hurdadd.application.port.out.TxRunner() {
        public <T> T inTx(java.util.function.Supplier<T> w) { return w.get(); }
    };

    static class InMemoryPaymentRepository implements PaymentRepository {
        final Map<UUID, Payment> byId = new HashMap<>();

        void seed(Payment payment) {
            byId.put(payment.getPaymentId().getValue(), payment);
        }

        UUID failSaveFor;

        @Override
        public Payment save(Payment payment) {
            if (payment.getPaymentId().getValue().equals(failSaveFor)) {
                throw new IllegalStateException("simulated optimistic lock failure");
            }
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
                    .filter(p -> p.getPaymentStatus() == PaymentStatus.PENDING_PSP
                            || p.getPaymentStatus() == PaymentStatus.AUTHORIZED
                            || (p.getPaymentStatus() == PaymentStatus.CREATED && p.getPspReference() != null))
                    .filter(p -> !p.getStatusChangedAt().isAfter(olderThan))
                    .filter(p -> !p.getStatusChangedAt().isBefore(newerThan))
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
                MerchantId.from(UUID.randomUUID()), ref);
        payment.markPending();
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
        PaymentReconciler reconciler = new PaymentReconciler(repository, pspSaysApproved, clock, stuckThreshold, maxAge, DIRECT);

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

        PaymentReconciler reconciler = new PaymentReconciler(repository, new StubPspClient(PspOutcome.APPROVED), clock, stuckThreshold, maxAge, DIRECT);
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

        PaymentReconciler reconciler = new PaymentReconciler(repository, new StubPspClient(PspOutcome.TIMEOUT), clock, stuckThreshold, maxAge, DIRECT);
        reconciler.reconcile();

        assertEquals(PaymentStatus.PENDING_PSP, repository.byId.values().iterator().next().getPaymentStatus());
    }

    private Payment crashedBeforeTx2(Instant createdAt, PspReference ref) {
        return Payment.reconstitute(new Money(new BigDecimal("50.00"), Currency.USD), PaymentId.from(UUID.randomUUID()),
                MerchantId.from(UUID.randomUUID()), PaymentStatus.CREATED, createdAt, 0, ref);
    }

    // PAY-203: crash between TX1 and TX2 leaves CREATED + reference; the PSP may have authorized it
    @Test
    void created_payment_with_reference_is_reconciled_to_authorized() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(crashedBeforeTx2(clock.instant().minusSeconds(121), new PspReference("psp-ref-crash")));

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.APPROVED), clock,
                Duration.ofMinutes(2), Duration.ofHours(24), DIRECT).reconcile();

        assertEquals(PaymentStatus.AUTHORIZED, repository.byId.values().iterator().next().getPaymentStatus());
    }

    @Test
    void created_payment_with_reference_is_failed_when_psp_declined() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(crashedBeforeTx2(clock.instant().minusSeconds(121), new PspReference("psp-ref-crash")));

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.DECLINED), clock,
                Duration.ofMinutes(2), Duration.ofHours(24), DIRECT).reconcile();

        assertEquals(PaymentStatus.FAILED, repository.byId.values().iterator().next().getPaymentStatus());
    }

    private Payment authorizedPayment(Instant statusChangedAt, Instant createdAt, PspReference ref) {
        return Payment.reconstitute(new Money(new BigDecimal("50.00"), Currency.USD), PaymentId.from(UUID.randomUUID()),
                MerchantId.from(UUID.randomUUID()), PaymentStatus.AUTHORIZED, createdAt, statusChangedAt, 0, ref);
    }

    // PAY-205: a stale AUTHORIZED payment is polled and taken to SETTLED
    @Test
    void stale_authorized_payment_is_settled_and_emits_event() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(authorizedPayment(clock.instant().minusSeconds(121), clock.instant().minusSeconds(500), new PspReference("psp-ref-auth")));

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.APPROVED), clock,
                Duration.ofMinutes(2), Duration.ofHours(24), DIRECT).reconcile();

        Payment settled = repository.byId.values().iterator().next();
        assertEquals(PaymentStatus.SETTLED, settled.getPaymentStatus());
        assertEquals(1, settled.pullEvents().size());
    }

    // the PSP reversed an authorized payment and no callback arrived: the poll must pick it up
    @Test
    void authorized_payment_reversed_at_the_psp_is_reconciled_to_reversed() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(authorizedPayment(clock.instant().minusSeconds(121), clock.instant().minusSeconds(500), new PspReference("psp-ref-reversed")));

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.REVERSED), clock,
                Duration.ofMinutes(2), Duration.ofHours(24), DIRECT).reconcile();

        assertEquals(PaymentStatus.REVERSED, repository.byId.values().iterator().next().getPaymentStatus());
    }

    // REVERSED is only legal from AUTHORIZED; a payment that never got authorized must not jump there
    @Test
    void reversed_answer_for_a_pending_payment_is_ignored() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        Duration stuckThreshold = Duration.ofMinutes(2);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(stuckPayment(clock.instant().minus(stuckThreshold).minusSeconds(1), new PspReference("psp-ref-odd")));

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.REVERSED), clock,
                stuckThreshold, Duration.ofHours(24), DIRECT).reconcile();

        assertEquals(PaymentStatus.PENDING_PSP, repository.byId.values().iterator().next().getPaymentStatus());
    }

    // PAY-205: candidate age is measured from status_changed_at — an old payment that changed status recently is left alone
    @Test
    void old_payment_with_recent_status_change_is_not_a_candidate() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(authorizedPayment(clock.instant().minusSeconds(10), clock.instant().minus(Duration.ofHours(3)), new PspReference("psp-ref-recent")));

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.APPROVED), clock,
                Duration.ofMinutes(2), Duration.ofHours(24), DIRECT).reconcile();

        assertEquals(PaymentStatus.AUTHORIZED, repository.byId.values().iterator().next().getPaymentStatus());
    }

    // PAY-205: one payment failing (e.g. OptimisticLockException on save) must not stop the others
    @Test
    void failure_on_one_payment_does_not_stop_the_batch() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        Payment poisoned = authorizedPayment(clock.instant().minusSeconds(121), clock.instant().minusSeconds(500), new PspReference("psp-ref-1"));
        Payment healthy = authorizedPayment(clock.instant().minusSeconds(121), clock.instant().minusSeconds(500), new PspReference("psp-ref-2"));
        repository.seed(poisoned);
        repository.seed(healthy);
        repository.failSaveFor = poisoned.getPaymentId().getValue();

        new PaymentReconciler(repository, new StubPspClient(PspOutcome.APPROVED), clock,
                Duration.ofMinutes(2), Duration.ofHours(24), DIRECT).reconcile();

        // (the in-memory repo shares objects, so the poisoned row's rollback is not asserted here; the point is the loop kept going)
        assertEquals(PaymentStatus.SETTLED, repository.byId.get(healthy.getPaymentId().getValue()).getPaymentStatus());
    }
}
