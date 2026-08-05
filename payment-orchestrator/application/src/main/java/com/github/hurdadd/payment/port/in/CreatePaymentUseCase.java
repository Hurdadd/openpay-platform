package com.github.hurdadd.payment.port.in;

import com.github.hurdadd.payment.port.in.results.CreatePaymentResult;

public interface CreatePaymentUseCase {
    CreatePaymentResult createPayment(CreatePaymentCommand command);
}
