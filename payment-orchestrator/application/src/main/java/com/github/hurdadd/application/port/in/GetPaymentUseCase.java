package com.github.hurdadd.application.port.in;

import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;


public interface GetPaymentUseCase {


    Payment getPayment(PaymentId paymentId);
}
