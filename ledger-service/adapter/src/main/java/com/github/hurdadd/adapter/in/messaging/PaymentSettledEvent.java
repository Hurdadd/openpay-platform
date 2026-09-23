package com.github.hurdadd.adapter.in.messaging;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public record PaymentSettledEvent(
        UUID paymentId,
        UUID merchantId,
        Currency currency,
        long amountMinor,
        Instant settledAt
) {
}