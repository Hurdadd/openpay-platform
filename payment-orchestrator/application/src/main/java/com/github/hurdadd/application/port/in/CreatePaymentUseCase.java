package com.github.hurdadd.application.port.in;

import com.github.hurdadd.application.port.in.results.CreatePaymentResult;

public interface CreatePaymentUseCase {
    CreatePaymentResult createPayment(CreatePaymentCommand command);
}
