package com.github.hurdadd.adapter;

import com.github.hurdadd.adapter.out.messaging.outbox.OutboxPublisher;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.EventTypes;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.JpaPaymentOutboxEntityRepository;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentOutboxEntity;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentSettledV1Payload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private final AtomicInteger openTransactions = new AtomicInteger();
    private final PlatformTransactionManager txManager = countingTransactionManager();
    private final JpaPaymentOutboxEntityRepository repository = mock(JpaPaymentOutboxEntityRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private OutboxPublisher publisher() {
        return new OutboxPublisher(repository, kafkaTemplate, new ObjectMapper(), txManager,
                "payment-events", Duration.ofSeconds(60));
    }

    private PlatformTransactionManager countingTransactionManager() {
        var tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(inv -> {
            openTransactions.incrementAndGet();
            return mock(TransactionStatus.class);
        });
        org.mockito.Mockito.doAnswer(inv -> openTransactions.decrementAndGet()).when(tm).commit(any());
        org.mockito.Mockito.doAnswer(inv -> openTransactions.decrementAndGet()).when(tm).rollback(any());
        return tm;
    }

    private static PaymentOutboxEntity row(UUID eventId, UUID paymentId) {
        var payload = new PaymentSettledV1Payload(paymentId, UUID.randomUUID(), 10000L, "IRR", Instant.now().toString());
        return new PaymentOutboxEntity(eventId, paymentId, EventTypes.PAYMENT_SETTLED_V1, payload);
    }

    @Test
    void publish_sends_json_envelope_built_from_the_row() {
        var eventId = UUID.randomUUID();
        var paymentId = UUID.randomUUID();
        when(repository.findClaimable(any())).thenReturn(List.of(row(eventId, paymentId)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publish();

        var jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment-events"), eq(paymentId.toString()), jsonCaptor.capture());
        var json = jsonCaptor.getValue();

        assertTrue(json.startsWith("{"), "value must be JSON but was: " + json);
        assertTrue(json.contains("\"eventId\":\"" + eventId + "\""), "eventId missing");
        assertTrue(json.contains("\"eventType\":\"PaymentSettled.v1\""), "eventType must be the wire format");
        assertTrue(json.contains("\"data\":{"), "data must be an object, not a JSON string");
        assertTrue(json.contains("\"amountMinor\":10000"), "amountMinor missing from data");
        assertFalse(json.contains("\\\""), "data was serialized twice");
        verify(repository).markPublished(eq(eventId), any());
    }

    @Test
    void events_of_one_payment_share_a_key_and_therefore_a_partition() {
        var paymentId = UUID.randomUUID();
        when(repository.findClaimable(any())).thenReturn(List.of(row(UUID.randomUUID(), paymentId), row(UUID.randomUUID(), paymentId)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publish();

        var keys = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("payment-events"), keys.capture(), anyString());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));

        // Kafka's default partitioning is murmur2(keyBytes) % numPartitions — same key, same partition
        int partitions = 12;
        assertEquals(partitionOf(keys.getAllValues().get(0), partitions), partitionOf(keys.getAllValues().get(1), partitions));
    }

    @Test
    void kafka_is_called_outside_any_database_transaction() {
        var paymentId = UUID.randomUUID();
        when(repository.findClaimable(any())).thenReturn(List.of(row(UUID.randomUUID(), paymentId)));
        var openDuringSend = new AtomicInteger(-1);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            openDuringSend.set(openTransactions.get());
            return CompletableFuture.completedFuture(null);
        });

        publisher().publish();

        assertEquals(0, openDuringSend.get(), "a transaction (connection + row locks) was open while sending to Kafka");
    }

    @Test
    void a_failed_send_releases_the_unsent_rows_and_keeps_the_sent_ones_published() {
        var first = row(UUID.randomUUID(), UUID.randomUUID());
        var second = row(UUID.randomUUID(), UUID.randomUUID());
        var third = row(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findClaimable(any())).thenReturn(List.of(first, second, third));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThrows(IllegalStateException.class, () -> publisher().publish());

        InOrder order = inOrder(repository);
        order.verify(repository).markPublished(eq(first.getId()), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> released = ArgumentCaptor.forClass(java.util.Collection.class);
        order.verify(repository).release(released.capture());
        assertEquals(List.of(second.getId(), third.getId()), List.copyOf(released.getValue()));
        verify(repository, never()).markPublished(eq(second.getId()), any());
        verify(repository, times(1)).markPublished(any(), any());
    }

    @Test
    void nothing_to_claim_means_no_kafka_call() {
        when(repository.findClaimable(any())).thenReturn(List.of());

        publisher().publish();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).release(anyCollection());
    }

    private static int partitionOf(String key, int partitions) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.apache.kafka.common.utils.Utils.toPositive(org.apache.kafka.common.utils.Utils.murmur2(bytes)) % partitions;
    }
}
