package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.in.PspCallbackHandler;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentNotFoundException;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;

public class PspCallbackService implements PspCallbackHandler {
    private final PaymentRepository paymentRepository;

    public PspCallbackService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public void handle(PspReference reference, PaymentStatus status) {
        Payment payment = paymentRepository.findByPspReference(reference)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "No payment found for psp reference " + reference.value()));

        // callbacks are at-least-once and can arrive out of order or repeat;
        // if we already applied this status (or moved past it), this is a no-op, not an error
        if (payment.getPaymentStatus() == status) {
            return;
        }
        if (!payment.getPaymentStatus().canTransitionTo(status)) {
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
