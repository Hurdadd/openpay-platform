package com.github.hurdadd.domain;

import java.time.Instant;

public record PaymentSettled(PaymentId paymentId, MerchantId merchantId, Money money, Instant settledAt)
        implements PaymentEvent {
}
