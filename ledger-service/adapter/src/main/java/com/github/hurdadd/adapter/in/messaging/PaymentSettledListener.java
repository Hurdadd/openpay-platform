package com.github.hurdadd.adapter.in.messaging;

import com.github.hurdadd.adapter.in.messaging.exception.UnprocessableMessageException;
import com.github.hurdadd.application.port.in.RecordLedgerEntryCommand;
import com.github.hurdadd.application.port.in.RecordLedgerEntryUseCase;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentSettledListener {
    private final RecordLedgerEntryUseCase useCase;
    private final ObjectMapper objectMapper;

    public PaymentSettledListener(RecordLedgerEntryUseCase useCase, ObjectMapper objectMapper) {
        this.useCase = useCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment-events", groupId = "ledger-service")
    public void listen(String message, Acknowledgment ack) {
        useCase.record(toCommand(message));
        ack.acknowledge();
    }

    private RecordLedgerEntryCommand toCommand(String message) {
        if (message == null || message.isBlank()) {
            throw new UnprocessableMessageException("Kafka message is null or blank");
        }

        final PaymentSettledEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, PaymentSettledEnvelope.class);
        } catch (JacksonException e) {
            throw new UnprocessableMessageException("Cannot parse Kafka message: " + message, e);
        }

        if (!"PaymentSettled.v1".equals(envelope.eventType())) {
            throw new UnprocessableMessageException("Unexpected eventType: " + envelope.eventType());
        }
        if (envelope.eventId() == null || envelope.data() == null) {
            throw new UnprocessableMessageException("Kafka message has no eventId or data: " + message);
        }

        PaymentSettledEvent event = envelope.data();
        return new RecordLedgerEntryCommand(
                envelope.eventId(), event.paymentId(), event.merchantId(), event.currency(), event.amountMinor());
    }
}
