package com.github.hurdadd.adapter.out.messaging.outbox;

import com.github.hurdadd.adapter.out.persistence.payment.outbox.JpaPaymentOutboxEntityRepository;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentOutboxEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Claim, publish, mark — in three separate steps so no database connection or row lock is held
 * while talking to Kafka:
 * <ol>
 *   <li>short transaction: lock a batch with SKIP LOCKED and stamp {@code claimed_at};</li>
 *   <li>no transaction: send each row to Kafka, in order;</li>
 *   <li>short transaction per row: mark it published.</li>
 * </ol>
 * A crash after the send but before the mark re-sends that row once its claim lease expires, so
 * delivery stays at-least-once and the ledger's idempotency key absorbs the duplicate. On a send
 * failure the unsent rows of the batch are released immediately and the batch stops, which keeps
 * the per-payment order of what was sent.
 */
@Service
public class OutboxPublisher {

    private final JpaPaymentOutboxEntityRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final TransactionTemplate tx;
    private final Duration claimLease;

    public OutboxPublisher(
            JpaPaymentOutboxEntityRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${outbox.kafka.topic}") String topic,
            @Value("${outbox.claim-lease:60s}") Duration claimLease
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.claimLease = claimLease;
    }

    private record Claimed(UUID id, UUID paymentId, EventEnvelope envelope) {}

    @Scheduled(fixedDelay = 5000)
    public void publish() {
        List<Claimed> batch = tx.execute(status -> claimBatch());
        if (batch == null || batch.isEmpty()) {
            return;
        }

        for (int i = 0; i < batch.size(); i++) {
            Claimed row = batch.get(i);
            try {
                kafkaTemplate.send(topic, row.paymentId().toString(), objectMapper.writeValueAsString(row.envelope())).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseFrom(batch, i);
                throw new IllegalStateException("Interrupted while publishing outbox event " + row.id(), e);
            } catch (ExecutionException e) {
                releaseFrom(batch, i);
                throw new IllegalStateException("Kafka publish failed for outbox event " + row.id(), e.getCause());
            } catch (RuntimeException e) {
                releaseFrom(batch, i);
                throw e;
            }
            tx.executeWithoutResult(status -> repository.markPublished(row.id(), Instant.now()));
        }
    }

    private List<Claimed> claimBatch() {
        Instant now = Instant.now();
        List<PaymentOutboxEntity> rows = repository.findClaimable(now.minus(claimLease));
        rows.forEach(row -> row.claim(now));
        return rows.stream()
                .map(row -> new Claimed(row.getId(), row.getPaymentId(),
                        new EventEnvelope(row.getId(), row.getEventType().wireFormat(), row.getPayload())))
                .toList();
    }

    private void releaseFrom(List<Claimed> batch, int firstUnsent) {
        List<UUID> ids = batch.subList(firstUnsent, batch.size()).stream().map(Claimed::id).toList();
        tx.executeWithoutResult(status -> repository.release(ids));
    }
}
