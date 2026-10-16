package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.in.PspCallbackHandler;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.domain.OutOfOrderCallbackException;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentNotFoundException;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;

public class PspCallbackService implements PspCallbackHandler {
    private final PaymentRepository paymentRepository;

    private final TxRunner tx;

    public PspCallbackService(PaymentRepository paymentRepository, TxRunner tx) {
        this.paymentRepository = paymentRepository;
        this.tx = tx;
    }

    // read + transition + save (payment row and its outbox row) is one transaction
    @Override
    public void handle(PspReference reference, PaymentStatus status) {
        tx.run(() -> apply(reference, status));
    }

    private void apply(PspReference reference, PaymentStatus status) {
        Payment payment = paymentRepository.findByPspReference(reference)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "No payment found for psp reference " + reference.value()));

        // callbacks are at-least-once and unordered:
        //  duplicate/stale (we are already at or past the reported state) -> no-op
        //  early (reported state is still ahead of us, e.g. SETTLED while PENDING_PSP) -> reject so the PSP retries
        PaymentStatus current = payment.getPaymentStatus();
        if (current == status) {
            return;
        }
        if (!current.canTransitionTo(status)) {
            if (current.canReach(status)) {
                throw new OutOfOrderCallbackException(
                        "Callback " + status + " is ahead of payment state " + current);
            }
            return;
        }

        switch (status) {
            case AUTHORIZED -> payment.authorize();
            case FAILED -> payment.fail();
            case SETTLED -> payment.settle();
            case REVERSED -> payment.reverse();
            default -> throw new IllegalArgumentException("PSP callback cannot report status " + status);
        }

        paymentRepository.save(payment);
    }
}
