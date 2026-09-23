package com.github.hurdadd.adapter.in.messaging;

import com.github.hurdadd.adapter.application.port.in.RecordLedgerEntryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class PaymentSettledListenerTest {

    @Test
    void acknowledge_happens_after_record_returns() {
        var useCase = mock(RecordLedgerEntryUseCase.class);
        var ack = mock(Acknowledgment.class);

        new PaymentSettledListener(useCase).listen("{}", ack);

        var inOrder = inOrder(useCase, ack);
        inOrder.verify(useCase).record("{}");
        inOrder.verify(ack).acknowledge();
    }
}
