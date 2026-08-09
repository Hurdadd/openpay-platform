package com.github.hurdadd.application.port.in.results;

import com.github.hurdadd.domain.Payment;

public record PaymentCreated(Payment payment) implements CreatePaymentResult {
}
