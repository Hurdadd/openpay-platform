package com.github.hurdadd.adapter.out.messaging.outbox;

import com.github.hurdadd.adapter.out.persistence.payment.outbox.JpaPaymentOutboxEntityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.ExecutionException;

@Service
public class OutboxPublisher {

    private final JpaPaymentOutboxEntityRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OutboxPublisher(
            JpaPaymentOutboxEntityRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${outbox.kafka.topic}") String topic
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Transactional
    @Scheduled(fixedDelay = 5000)
    public void publish() {
        var unpublished = repository.findUnpublished();

        for (var entity : unpublished) {
            var envelope = new EventEnvelope(
                    entity.getId(),
                    entity.getEventType().wireFormat(),
                    entity.getPayload()
            );

            try {
                kafkaTemplate.send(
                                topic,
                                entity.getPaymentId().toString(),
                                objectMapper.writeValueAsString(envelope)
                        )
                        .get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing outbox event " + entity.getId(), e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Kafka publish failed for outbox event " + entity.getId(), e.getCause());
            }

            entity.markPublished(Instant.now());
        }
    }
}
