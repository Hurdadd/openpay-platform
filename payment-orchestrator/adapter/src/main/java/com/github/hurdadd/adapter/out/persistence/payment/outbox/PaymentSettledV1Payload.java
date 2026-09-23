package com.github.hurdadd.adapter.out.persistence.payment.outbox;

import java.time.Instant;
import java.util.UUID;

public record PaymentSettledV1Payload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        Instant settledAt
) {}


