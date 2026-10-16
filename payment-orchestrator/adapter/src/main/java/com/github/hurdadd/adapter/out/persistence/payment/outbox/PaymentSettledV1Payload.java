package com.github.hurdadd.adapter.out.persistence.payment.outbox;

import java.util.UUID;

public record PaymentSettledV1Payload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String settledAt  // ISO-8601; plain String so Hibernate's JSON mapper needs no java.time module
) {}


