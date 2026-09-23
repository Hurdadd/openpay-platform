package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.out.PaymentRepository;
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
 * Backstop for payments stuck in PENDING_PSP because their callback never arrived (dropped, PSP-side
 * failure, network partition). Polls the PSP directly for each candidate and applies whatever status
 * it reports, going through the same transition guards the callback path uses.
 */
public class PaymentReconciler {

    private final PaymentRepository paymentRepository;
    private final PspClient pspClient;
    private final Clock clock;
    private final Duration stuckThreshold;
    private final Duration maxAge;

    public PaymentReconciler(PaymentRepository paymentRepository,
                              PspClient pspClient,
                              Clock clock,
                              Duration stuckThreshold,
                              Duration maxAge) {
        this.paymentRepository = paymentRepository;
        this.pspClient = pspClient;
        this.clock = clock;
        this.stuckThreshold = stuckThreshold;
        this.maxAge = maxAge;
    }

    public void reconcile() {
        Instant now = clock.instant();
        Instant olderThan = now.minus(stuckThreshold);
        Instant newerThan = now.minus(maxAge);

        List<Payment> candidates = paymentRepository.findStuckPending(olderThan, newerThan);
        for (Payment payment : candidates) {
            reconcileOne(payment);
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
            return;
        }

        PaymentStatus resolved = toStatus(result.pspOutcome());
        if (resolved == null || !payment.getPaymentStatus().canTransitionTo(resolved)) {
            return;
        }

        switch (resolved) {
            case AUTHORIZED -> payment.authorize();
            case FAILED -> payment.fail();
            default -> throw new IllegalStateException("Unexpected reconciler target status " + resolved);
        }
        paymentRepository.save(payment);
    }

    private PaymentStatus toStatus(PspOutcome outcome) {
        return switch (outcome) {
            case APPROVED -> PaymentStatus.AUTHORIZED;
            case DECLINED -> PaymentStatus.FAILED;
            case TIMEOUT -> null; // PSP itself doesn't know yet either — wait for the next poll
        };
    }
}
