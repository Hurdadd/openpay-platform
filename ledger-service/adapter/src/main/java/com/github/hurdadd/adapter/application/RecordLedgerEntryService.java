package com.github.hurdadd.adapter.application;

import com.github.domain.JournalEntry;
import com.github.domain.Posting;
import com.github.hurdadd.adapter.application.port.in.RecordLedgerEntryUseCase;
import com.github.hurdadd.adapter.application.port.out.LedgerRepository;
import com.github.hurdadd.adapter.in.messaging.PaymentSettledEnvelope;
import com.github.hurdadd.adapter.in.messaging.PaymentSettledEvent;
import com.github.hurdadd.adapter.in.messaging.exception.UnprocessableMessageException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
public class RecordLedgerEntryService implements RecordLedgerEntryUseCase {

    private final LedgerRepository ledgerRepository;
    private final ObjectMapper objectMapper;

    public RecordLedgerEntryService(LedgerRepository ledgerRepository, ObjectMapper objectMapper) {
        this.ledgerRepository = ledgerRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(String message) {
        PaymentSettledEnvelope envelope = parse(message);
        PaymentSettledEvent event = envelope.data();

        UUID clearingId = ledgerRepository.ensureAccount(
                "psp_clearing", "CLEARING", event.currency().getCurrencyCode());
        UUID merchantAccountId = ledgerRepository.ensureAccount(
                "merchant:" + event.merchantId(), "MERCHANT_PAYABLE", event.currency().getCurrencyCode());

        JournalEntry entry = buildBalancedEntry(event, clearingId, merchantAccountId);

        ledgerRepository.save(entry, envelope.eventId().toString());
    }

    private JournalEntry buildBalancedEntry(PaymentSettledEvent event, UUID clearingId, UUID merchantAccountId) {
        if (event.amountMinor() <= 0) {
            throw new IllegalArgumentException("Settlement amount must be positive: " + event.amountMinor());
        }

        List<Posting> postings = List.of(
                new Posting(event.amountMinor(), clearingId.toString()),
                new Posting(-event.amountMinor(), merchantAccountId.toString()));

        return new JournalEntry(postings, "Settlement " + event.paymentId(), event.currency());
    }

    private PaymentSettledEnvelope parse(String message) {
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

        if (envelope.eventId() == null) {
            throw new UnprocessableMessageException("Kafka message has no eventId: " + message);
        }

        return envelope;
    }
}