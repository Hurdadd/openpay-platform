package com.github.hurdadd.adapter.in.messaging;


import com.github.hurdadd.adapter.application.port.in.RecordLedgerEntryUseCase;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PaymentSettledListener {
    private final RecordLedgerEntryUseCase useCase;
    public PaymentSettledListener(RecordLedgerEntryUseCase useCase) {
        this.useCase = useCase;
    }

    @KafkaListener(topics = "payment-events" , groupId = "ledger-service")
    public void listen(String message , Acknowledgment ack) {
        useCase.record(message);
        ack.acknowledge();
    }
}
