package com.github.hurdadd.payment.port.in.results;

import com.github.hurdadd.payment.Payment;

public record PaymentCreated(Payment payment) implements CreatePaymentResult {
}
