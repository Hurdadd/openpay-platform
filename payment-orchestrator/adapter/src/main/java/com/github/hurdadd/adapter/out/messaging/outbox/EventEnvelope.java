package com.github.hurdadd.adapter.out.messaging.outbox;

import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        String eventType,
        Object data
) {
}
