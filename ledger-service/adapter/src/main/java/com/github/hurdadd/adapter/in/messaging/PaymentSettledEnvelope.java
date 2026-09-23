package com.github.hurdadd.adapter.in.messaging;

import java.util.UUID;

public record PaymentSettledEnvelope(
        UUID eventId,
        String eventType,
        PaymentSettledEvent data
) {
}
