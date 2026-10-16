package com.github.hurdadd.adapter.in.messaging;

import com.github.hurdadd.adapter.in.messaging.exception.UnprocessableMessageException;
import com.github.hurdadd.application.port.in.RecordLedgerEntryCommand;
import com.github.hurdadd.application.port.in.RecordLedgerEntryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentSettledListenerTest {

    static final String PAYMENT = "11111111-1111-1111-1111-111111111111";
    static final String MERCHANT = "22222222-2222-2222-2222-222222222222";
    static final String EVENT = "33333333-3333-3333-3333-333333333333";

    RecordLedgerEntryUseCase useCase = mock(RecordLedgerEntryUseCase.class);
    Acknowledgment ack = mock(Acknowledgment.class);
    PaymentSettledListener listener = new PaymentSettledListener(useCase, new ObjectMapper());

    private static String envelope(String eventId, String eventType) {
        return """
                {%s"eventType":"%s","data":{"paymentId":"%s","merchantId":"%s","amountMinor":10000,"currency":"IRR","settledAt":"2026-09-21T10:00:00Z"}}"""
                .formatted(eventId == null ? "" : "\"eventId\":\"" + eventId + "\",", eventType, PAYMENT, MERCHANT);
    }

    @Test
    void acknowledge_happens_after_record_returns() {
        listener.listen(envelope(EVENT, "PaymentSettled.v1"), ack);

        var inOrder = inOrder(useCase, ack);
        inOrder.verify(useCase).record(new RecordLedgerEntryCommand(
                UUID.fromString(EVENT), UUID.fromString(PAYMENT), UUID.fromString(MERCHANT),
                Currency.getInstance("IRR"), 10000));
        inOrder.verify(ack).acknowledge();
    }

    @Test
    void bad_messages_are_unprocessable_and_never_reach_the_use_case_or_ack() {
        for (String bad : new String[]{null, "  ", "not-json{", envelope(EVENT, "PaymentFailed.v1"),
                envelope(null, "PaymentSettled.v1")}) {
            assertThrows(UnprocessableMessageException.class, () -> listener.listen(bad, ack));
        }
        verifyNoInteractions(useCase, ack);
    }
}
