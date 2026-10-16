package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.in.GetPaymentUseCase;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import com.github.hurdadd.domain.PaymentNotFoundException;

public class GetPaymentService implements GetPaymentUseCase {

    private final PaymentRepository repository;

    public GetPaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Payment getPayment(PaymentId paymentId) {
        return repository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "No payment with id " + paymentId.getValue()));
    }
}
