package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Backstop for payments stuck in PENDING_PSP (or in CREATED with a reference, after a crash between the
 * create and PSP-result transactions) because their callback never arrived (dropped, PSP-side
 * failure, network partition), and for AUTHORIZED payments whose SETTLED callback never arrived.
 * Polls the PSP directly for each candidate and applies whatever status it reports, going through
 * the same transition guards the callback path uses. Each candidate is isolated: its own transaction
 * and its own try/catch, so one failing payment never stops the rest of the batch.
 */
public class PaymentReconciler {

    private static final System.Logger LOG = System.getLogger(PaymentReconciler.class.getName());

    private final PaymentRepository paymentRepository;
    private final PspClient pspClient;
    private final Clock clock;
    private final Duration stuckThreshold;
    private final Duration maxAge;
    private final TxRunner tx;

    public PaymentReconciler(PaymentRepository paymentRepository,
                              PspClient pspClient,
                              Clock clock,
                              Duration stuckThreshold,
                              Duration maxAge,
                              TxRunner tx) {
        this.paymentRepository = paymentRepository;
        this.pspClient = pspClient;
        this.clock = clock;
        this.stuckThreshold = stuckThreshold;
        this.maxAge = maxAge;
        this.tx = tx;
    }

    public void reconcile() {
        Instant now = clock.instant();
        Instant olderThan = now.minus(stuckThreshold);
        Instant newerThan = now.minus(maxAge);

        List<Payment> candidates = paymentRepository.findStuckPending(olderThan, newerThan);
        for (Payment payment : candidates) {
            try {
                reconcileOne(payment);
            } catch (RuntimeException e) {
                // e.g. an optimistic-lock failure because a callback won the race; the next run re-reads and retries
                LOG.log(System.Logger.Level.WARNING, "Reconcile failed for payment " + payment.getPaymentId().getValue(), e);
            }
        }
    }

    private void reconcileOne(Payment payment) {
        if (payment.getPspReference() == null) {
            // never got far enough to have a PSP reference to ask about — nothing to reconcile yet,
            // the max-age cap is what eventually surfaces this as permanently stuck
            return;
        }

        PspResult result;
        try {
            result = pspClient.checkStatus(payment.getPspReference());
        } catch (RuntimeException e) {
            // "PSP didn't answer" is not "PSP said failed" — leave the payment PENDING_PSP and let
            // the next scheduled run retry; ResilientPspClient already applied retry/backoff/circuit-breaker
            LOG.log(System.Logger.Level.WARNING, "PSP status check failed for payment " + payment.getPaymentId().getValue(), e);
            return;
        }

        if (result.pspOutcome() == PspOutcome.TIMEOUT) {
            return; // PSP itself doesn't know yet either — wait for the next poll
        }
        // the PSP call above ran outside any transaction; only the write is transactional, and it
        // re-reads the row so a callback that landed in the meantime is not overwritten with stale state
        tx.run(() -> applyResolved(payment, result.pspOutcome()));
    }

    private void applyResolved(Payment stale, PspOutcome outcome) {
        Payment payment = paymentRepository.findById(stale.getPaymentId()).orElse(null);
        if (payment == null) {
            return;
        }
        PaymentStatus resolved = toStatus(outcome, payment.getPaymentStatus());
        // crashed between TX1 and TX2: the payment never left CREATED, but the PSP did see the reference
        if (payment.getPaymentStatus() == PaymentStatus.CREATED && resolved == PaymentStatus.AUTHORIZED) {
            payment.markPending();
        }
        if (!payment.getPaymentStatus().canTransitionTo(resolved)) {
            return;
        }

        switch (resolved) {
            case AUTHORIZED -> payment.authorize();
            case FAILED -> payment.fail();
            case SETTLED -> payment.settle(); // writes the outbox row in the same transaction via save()
            case REVERSED -> payment.reverse(); // only legal from AUTHORIZED; guarded by canTransitionTo above
            default -> throw new IllegalStateException("Unexpected reconciler target status " + resolved);
        }
        paymentRepository.save(payment);
    }

    // PspOutcome has no SETTLED: APPROVED for an already-AUTHORIZED payment means the PSP captured it
    private PaymentStatus toStatus(PspOutcome outcome, PaymentStatus current) {
        return switch (outcome) {
            case APPROVED -> current == PaymentStatus.AUTHORIZED ? PaymentStatus.SETTLED : PaymentStatus.AUTHORIZED;
            case DECLINED -> PaymentStatus.FAILED;
            case REVERSED -> PaymentStatus.REVERSED;
            case TIMEOUT -> throw new IllegalStateException("TIMEOUT is filtered before the write");
        };
    }
}
